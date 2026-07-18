package io.github.intisy.ai.claude;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of claude-code-auth's original {@code src/plugin/request.ts} (Bucket A of
 * {@code .superpowers/port-grounding-map.md}; the TS file itself was DELETED in SP-2 -- it had
 * become dead code, superseded by this class): {@code mergeBeta}, {@code prepareClaudeRequest},
 * and the HEADER-PARSE half of {@code parseResetMs}. The exponential-backoff fallback branch of
 * {@code parseResetMs} (request.ts:89-91) is DELIBERATELY DROPPED here -- it reads
 * {@code getDefaultCooldownSeconds()}/{@code getMaxCooldownSeconds()} from core's config store
 * (Bucket B I/O), and belongs to core-auth's {@code RateLimitMath} instead. This class returns
 * {@code null} from {@link #parseResetMs} where the TS would have fallen through to that branch;
 * the caller is expected to invoke {@code RateLimitMath} in that case.
 *
 * <p><b>SP-2 (core-ir):</b> {@code prepareClaudeRequest} used to hand-rewrite the parsed request
 * body's {@code system} field directly on the raw JSON {@code Map} tree ({@code
 * ensureClaudeCodeSystem}, now DELETED). It now round-trips the body through core-ir instead --
 * {@code AnthropicTranslator.decodeRequest} (inbound Anthropic wire -&gt; {@link IrRequest}),
 * {@link #ensureClaudeCodeSystemBlocks} (the identity-block dedup/prepend, now operating on
 * {@code IrRequest.system}'s neutral {@link Block} list), then {@code encodeRequest} back to the
 * Anthropic body actually sent upstream. Since claude's upstream IS Anthropic, this is an IR
 * round trip on the SAME vendor format -- semantically lossless (core-ir's extensions bags carry
 * anything with no neutral IR home), though the re-encoded JSON's key order can differ from the
 * original (never wire-significant). Bearer/anthropic-version/anthropic-beta headers stay
 * provider-own upstream-auth concerns, applied to the encoded body exactly as before -- NOT
 * format translation, so they are untouched by this migration.
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

    // ---- ensureClaudeCodeSystemBlocks (request.ts:16-30, ported to the IR for SP-2) ------------

    /**
     * Ensures {@code system} starts with the Claude-Code identity block, without duplicating it
     * if already present -- the IR-level replacement for the old raw-JSON {@code
     * ensureClaudeCodeSystem}. Once normalized into core-ir's neutral {@link Block} list, the
     * original three-way TS/JSON branch (string / array / else, since JS distinguishes {@code
     * typeof} string from {@code Array.isArray}) collapses into ONE rule: a bare wire string and
     * a single-element wire array decode to the IDENTICAL one-{@link TextBlock} shape, so the
     * same "does it already start with the identity block" check handles every case correctly --
     * absent/{@code null}/non-string-non-array system, an empty array, a plain string, and a
     * block array all decode through {@code AnthropicRequestCodec} to either {@code null}/empty
     * or a {@code List<Block>}, verified against every case the deleted method's own unit tests
     * covered.
     *
     * @return {@code [identity]} when {@code system} is {@code null}/empty; {@code system}
     *     unchanged when it already starts with the identity block; otherwise the identity block
     *     prepended.
     */
    public static List<Block> ensureClaudeCodeSystemBlocks(List<Block> system) {
        if (system == null || system.isEmpty()) {
            List<Block> singleton = new ArrayList<>();
            singleton.add(freshIdentityBlock());
            return singleton;
        }
        Block first = system.get(0);
        if (isIdentityBlock(first)) {
            if (system.size() == 1 && first.extensions == null) {
                // Force a non-null (empty) extensions map on the EXISTING block -- in place, so
                // any real cache_control it carries is preserved untouched -- so the encode side
                // can never collapse it back into a bare wire STRING. Relevant when the inbound
                // wire `system` was itself the identity string verbatim, which core-ir's
                // AnthropicTranslator would otherwise reproduce as a string (a valid but different
                // wire shape than the original ad hoc code's "always an array" behavior). Safe: a
                // block only looks "plain" (no cache_control, no extensions) if it came from a
                // bare string OR a metadata-free single-element array -- in both cases there is
                // nothing to lose; a block with a REAL cache_control already has extensions==null
                // possibly true too, which is exactly why this only forces extensions, never
                // touching cacheControl.
                first.extensions = new LinkedHashMap<>();
            }
            return system; // already present (with or without extra turns after it) -- untouched
        }
        List<Block> merged = new ArrayList<>();
        merged.add(freshIdentityBlock());
        merged.addAll(system);
        return merged;
    }

    private static boolean isIdentityBlock(Block b) {
        return b instanceof TextBlock && CLAUDE_CODE_SYSTEM.equals(((TextBlock) b).text);
    }

    private static TextBlock freshIdentityBlock() {
        TextBlock t = new TextBlock(CLAUDE_CODE_SYSTEM);
        t.extensions = new LinkedHashMap<>();
        return t;
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
        boolean streaming = false;

        if (bodyText != null && !bodyText.isEmpty()) {
            Object parsed;
            try {
                parsed = json.parse(bodyText);
            } catch (RuntimeException e) {
                parsed = null; // matches the TS try/catch { parsed = undefined }
            }
            // Only a genuine JSON object round-trips through the IR (an Anthropic Messages
            // request body always is one); malformed JSON, or a truthy non-object (an
            // unreachable-in-practice shape for this endpoint -- e.g. a bare JSON number), is
            // left as the ORIGINAL bytes verbatim rather than fabricating IR structure that was
            // never there. (The old code still re-stringified a truthy non-object via
            // ensureClaudeCodeSystem's no-op passthrough + json.stringify; skipping that here is
            // an idempotent no-op difference for realistic inputs, not a behavior change.)
            if (parsed instanceof Map) {
                io.github.intisy.ai.ir.spi.JsonCodec irJson = new IrJsonCodecAdapter(json);
                AnthropicTranslator translator = new AnthropicTranslator(irJson);
                IrRequest ir = translator.decodeRequest(bodyText);
                streaming = ir.stream;
                ir.system = ensureClaudeCodeSystemBlocks(ir.system);
                bodyText = translator.encodeRequest(ir);
            }
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
