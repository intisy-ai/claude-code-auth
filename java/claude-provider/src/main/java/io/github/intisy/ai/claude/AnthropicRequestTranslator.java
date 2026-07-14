package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of claude-code-auth's {@code src/plugin/request.ts} (Bucket A of
 * {@code .superpowers/port-grounding-map.md}): {@code ensureClaudeCodeSystem},
 * {@code mergeBeta}, {@code prepareClaudeRequest}, and the HEADER-PARSE half of
 * {@code parseResetMs}. The exponential-backoff fallback branch of {@code parseResetMs}
 * (request.ts:89-91) is DELIBERATELY DROPPED here -- it reads
 * {@code getDefaultCooldownSeconds()}/{@code getMaxCooldownSeconds()} from core's config store
 * (Bucket B I/O), and belongs to core-auth's {@code RateLimitMath} instead. This class returns
 * {@code null} from {@link #parseResetMs} where the TS would have fallen through to that branch;
 * the caller is expected to invoke {@code RateLimitMath} in that case.
 *
 * <p>Only the {@link JsonCodec} SPI is used (to parse/stringify the request body) -- no
 * HttpClient/Store, no gson/java.net/java.nio/reflection/threads/System.getenv, so this class is
 * TeaVM-transpilable (see {@code :claude-teavm}).
 */
public final class AnthropicRequestTranslator {

    public static final String ANTHROPIC_API_BASE = "https://api.anthropic.com";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final String ANTHROPIC_OAUTH_BETA = "oauth-2025-04-20";
    public static final String CLAUDE_CODE_SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude.";

    private AnthropicRequestTranslator() {
    }

    // ---- ensureClaudeCodeSystem (request.ts:16-30) -------------------------------------------

    /**
     * Injects the mandatory Claude-Code system-identity block into a parsed request body,
     * mirroring the TS dedup rules EXACTLY:
     * <ul>
     *   <li>{@code body} not a JSON object (including {@code null}) -&gt; returned unchanged.</li>
     *   <li>{@code body.system} a String equal to {@link #CLAUDE_CODE_SYSTEM} -&gt; replaced with
     *       {@code [identity]} (no duplicate second block).</li>
     *   <li>{@code body.system} any OTHER String -&gt; {@code [identity, {type:"text",
     *       text: originalString}]}.</li>
     *   <li>{@code body.system} a List whose first element is already the identity block ({@code
     *       {type:"text", text: CLAUDE_CODE_SYSTEM}}) -&gt; left untouched (dedup).</li>
     *   <li>{@code body.system} any OTHER List -&gt; identity block prepended.</li>
     *   <li>{@code body.system} absent, {@code null}, or any non-String/non-List value (matches
     *       the TS {@code else} branch -- note {@code null} falls here too, since
     *       {@code typeof null !== "string"} and {@code Array.isArray(null)} is false in JS)
     *       -&gt; replaced with {@code [identity]}.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static Object ensureClaudeCodeSystem(Object body) {
        if (!(body instanceof Map)) return body;
        Map<String, Object> map = (Map<String, Object>) body;
        Object system = map.get("system");
        if (system instanceof String) {
            if (system.equals(CLAUDE_CODE_SYSTEM)) {
                map.put("system", singletonList(identityBlock()));
            } else {
                List<Object> list = new ArrayList<>();
                list.add(identityBlock());
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", system);
                list.add(textBlock);
                map.put("system", list);
            }
        } else if (system instanceof List) {
            List<?> list = (List<?>) system;
            Object first = list.isEmpty() ? null : list.get(0);
            if (!isIdentityBlock(first)) {
                List<Object> merged = new ArrayList<>();
                merged.add(identityBlock());
                merged.addAll(list);
                map.put("system", merged);
            }
        } else {
            map.put("system", singletonList(identityBlock()));
        }
        return map;
    }

    private static boolean isIdentityBlock(Object first) {
        if (!(first instanceof Map)) return false;
        Map<?, ?> m = (Map<?, ?>) first;
        return "text".equals(m.get("type")) && CLAUDE_CODE_SYSTEM.equals(m.get("text"));
    }

    private static Map<String, Object> identityBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", CLAUDE_CODE_SYSTEM);
        return m;
    }

    private static List<Object> singletonList(Object o) {
        List<Object> l = new ArrayList<>();
        l.add(o);
        return l;
    }

    // ---- mergeBeta (request.ts:32-35) --------------------------------------------------------

    /** Appends {@link #ANTHROPIC_OAUTH_BETA} to an existing header value, deduped by substring. */
    public static String mergeBeta(String existing) {
        if (JsCoercion.isFalsyString(existing)) return ANTHROPIC_OAUTH_BETA;
        // Matches the TS `existing.includes(...)` literally: a SUBSTRING match, not a
        // comma-list-membership check -- e.g. existing="foo-oauth-2025-04-20-bar" is treated as
        // already containing the beta flag and left unchanged. Ambiguous but intentional per the
        // TS source; not "improved" here.
        return existing.contains(ANTHROPIC_OAUTH_BETA) ? existing : existing + "," + ANTHROPIC_OAUTH_BETA;
    }

    // ---- prepareClaudeRequest (request.ts:38-75) ----------------------------------------------

    /** Mirrors the TS {@code init} shape: {@code {method, headers, body}}. */
    public static final class RequestInit {
        public String method;
        public Map<String, String> headers;
        public String body;
    }

    /** Mirrors the TS return shape: {@code {request, init, streaming}}. */
    public static final class PreparedRequest {
        public String request;
        public String method;
        public Map<String, String> headers;
        /** {@code null} when stripped for a GET/HEAD method (undici rejects a body on those). */
        public String body;
        public boolean streaming;
    }

    /**
     * Rewrites an inbound request onto the Anthropic API: strips/rewrites headers, sets
     * {@code Authorization: Bearer <access>}, resolves the request path, injects the
     * Claude-Code system block into a JSON body, and strips the body for GET/HEAD (Node's undici
     * rejects any body on those methods, unlike Bun -- request.ts:64-65).
     *
     * @param json   used to parse/stringify the (optional) JSON body -- the only SPI this class
     *               needs.
     * @param url    request URL, absolute or path-only. Path resolution is a hand-rolled regex
     *               approximation of {@code new URL(url, ANTHROPIC_API_BASE).pathname} (no
     *               {@code java.net.URL}/{@code URI} -- not TeaVM-transpilable): it strips any
     *               {@code scheme://host} prefix and any query/fragment, defaulting to
     *               {@code /v1/messages} when the remaining path is empty. This covers both
     *               realistic input shapes for this provider (an absolute
     *               {@code https://api.anthropic.com/...} URL, or an origin-relative
     *               {@code /v1/...} path) but does NOT implement the full WHATWG relative-URL
     *               resolution algorithm (dot-segment removal, percent-encoding, etc.) -- noted
     *               as an intentional TeaVM-safety-driven simplification, since the loader never
     *               calls this with a bare origin or an exotic relative reference.
     */
    public static PreparedRequest prepareClaudeRequest(JsonCodec json, String url, RequestInit init, String access) {
        String path = pathOf(url);

        String bodyText = init.body;
        Object parsed = null;
        if (bodyText != null && !bodyText.isEmpty()) {
            try {
                parsed = json.parse(bodyText);
            } catch (RuntimeException e) {
                parsed = null; // matches the TS try/catch { parsed = undefined }
            }
        }

        boolean streaming = false;
        if (parsed != null && JsCoercion.isTruthy(parsed)) {
            Object streamVal = (parsed instanceof Map) ? ((Map<?, ?>) parsed).get("stream") : null;
            streaming = JsCoercion.isTruthy(streamVal);
        }

        if (parsed != null && JsCoercion.isTruthy(parsed)) {
            Object updated = ensureClaudeCodeSystem(parsed);
            bodyText = json.stringify(updated);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (init.headers != null) {
            for (Map.Entry<String, String> e : init.headers.entrySet()) {
                headers.put(e.getKey() == null ? null : e.getKey().toLowerCase(), e.getValue());
            }
        }
        headers.remove("x-api-key");
        headers.remove("host");
        headers.remove("content-length");
        headers.remove("accept-encoding");

        headers.put("authorization", "Bearer " + access);
        String existingVersion = headers.get("anthropic-version");
        headers.put("anthropic-version", JsCoercion.isFalsyString(existingVersion) ? ANTHROPIC_VERSION : existingVersion);
        headers.put("anthropic-beta", mergeBeta(headers.get("anthropic-beta")));
        headers.put("content-type", "application/json");

        String method = JsCoercion.isFalsyString(init.method) ? "POST" : init.method;

        PreparedRequest result = new PreparedRequest();
        result.request = ANTHROPIC_API_BASE + path;
        result.method = method;
        result.headers = headers;
        result.body = bodyText;
        if ("GET".equals(method) || "HEAD".equals(method)) {
            result.body = null;
        }
        result.streaming = streaming;
        return result;
    }

    private static final Pattern SCHEME_HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*");

    static String pathOf(String url) {
        if (url == null) return "/v1/messages";
        String stripped = url;
        int hashIdx = stripped.indexOf('#');
        if (hashIdx >= 0) stripped = stripped.substring(0, hashIdx);
        int queryIdx = stripped.indexOf('?');
        if (queryIdx >= 0) stripped = stripped.substring(0, queryIdx);

        String path;
        Matcher m = SCHEME_HOST.matcher(stripped);
        if (m.find() && m.start() == 0) {
            path = stripped.substring(m.end());
        } else if (stripped.startsWith("/")) {
            path = stripped;
        } else {
            // Relative reference (no leading '/') resolved against a base whose own path is
            // empty -- WHATWG merges to "/" + relative in that case.
            path = "/" + stripped;
        }
        return path.isEmpty() ? "/v1/messages" : path;
    }

    // ---- parseResetMs, HEADER-PARSE HALF ONLY (request.ts:78-88) -------------------------------

    /**
     * Reads the rate-limit reset (epoch ms) from response headers: the unified reset header
     * first, then {@code retry-after}. Returns {@code null} when NEITHER header yields a usable
     * value -- the TS instead falls through to an exponential-backoff fallback (request.ts:89-91)
     * that this port deliberately does NOT reimplement (see class javadoc); the caller must
     * invoke core-auth's {@code RateLimitMath} for that case.
     *
     * @param headers   response headers, looked up case-insensitively (matches the Fetch
     *                  {@code Headers} object's case-insensitive {@code get}).
     * @param nowMillis the current time in epoch ms, taken as an explicit parameter (instead of
     *                  reading a clock internally) so parity tests stay fully deterministic --
     *                  this mirrors the TS's implicit {@code Date.now()} call.
     */
    public static Long parseResetMs(Map<String, String> headers, long nowMillis) {
        String unified = getHeaderCI(headers, "anthropic-ratelimit-unified-reset");
        if (!JsCoercion.isFalsyString(unified)) {
            double secs = JsCoercion.jsNumber(unified);
            if (!Double.isNaN(secs) && secs > 0) return (long) (secs * 1000);
        }
        String retryAfter = getHeaderCI(headers, "retry-after");
        if (!JsCoercion.isFalsyString(retryAfter)) {
            double secs = JsCoercion.jsNumber(retryAfter);
            if (!Double.isNaN(secs) && secs > 0) return nowMillis + (long) (secs * 1000);
        }
        return null;
    }

    private static String getHeaderCI(Map<String, String> headers, String name) {
        if (headers == null) return null;
        String direct = headers.get(name);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
