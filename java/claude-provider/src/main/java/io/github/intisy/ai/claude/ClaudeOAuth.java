package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM-only OAuth side-path for the Claude provider (mirrors {@link ClaudeModelsFetch}'s shape):
 * builds the real claude.ai authorize URL (PKCE S256, verifier packed into {@code state}) and
 * ports {@code authorizeClaude}/{@code encodeState}/{@code decodeState}/{@code exchangeClaude}
 * verbatim from {@code providers/claude-code-auth/src/oauth/oauth.ts} +
 * {@code src/constants.ts}. Claude is a PUBLIC client -- no secret. Never logs
 * code/verifier/state/tokens.
 *
 * <p>{@link #authorizeInfo()}/{@link #exchangeValues} are the typed {@link
 * io.github.intisy.ai.shared.routing.OAuthProvider} entry points {@link ClaudeProvider} delegates
 * to. There is no HttpResponse wrapper anymore -- nothing but the retired {@code GET/POST
 * /v1/oauth/*} branches ever called the old {@code authorize()}/{@code exchange()} methods.
 * {@code exchangeValues} throws {@link IllegalStateException} on any failure (missing PKCE
 * verifier, token-exchange transport/HTTP failure, missing refresh token) since the typed {@code
 * Map<String,Object> exchange(...)} contract has no status/error-body shape to carry a reason in
 * -- the caller (e.g. the dashboard's OAuth admin) catches and translates, the same way it already
 * catches {@code handler.handle()} exceptions today.
 */
final class ClaudeOAuth {
    private static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";
    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    // CLAUDE_SCOPES.join(" ") from constants.ts: ["org:create_api_key", "user:profile", "user:inference"]
    private static final String SCOPES = "org:create_api_key user:profile user:inference";
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RNG = new SecureRandom();

    private ClaudeOAuth() {
    }

    /** {@link io.github.intisy.ai.shared.routing.OAuthProvider#authorize}. */
    static AuthorizeInfo authorizeInfo() {
        // PKCE S256, matching @openauthjs/openauth's generatePKCE(length = 64) exactly: verifier
        // is base64url(64 random bytes); challenge = base64url(sha256(TextEncoder().encode(verifier))),
        // i.e. sha256 over the UTF-8 bytes of the base64url-encoded verifier STRING (not the raw
        // random bytes).
        byte[] raw = new byte[64];
        RNG.nextBytes(raw);
        String verifier = URL64.encodeToString(raw);
        String challenge = URL64.encodeToString(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
        String state = encodeState(verifier);
        // Param set + order mirrors authorizeClaude() in oauth.ts exactly: code, client_id,
        // response_type, redirect_uri, scope, code_challenge, code_challenge_method, state.
        String url = AUTHORIZE_URL
                + "?code=true"
                + "&client_id=" + enc(CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=" + enc(SCOPES)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + enc(state);
        // loopbackPort/loopbackPath stay null -- Claude is a "paste" flow, not a local-redirect-
        // listener flow (mirrors the old JSON, which never carried them either).
        return new AuthorizeInfo(url, "paste", state, null, null);
    }

    /** {@link io.github.intisy.ai.shared.routing.OAuthProvider#exchange}: parses {@code {code,state}} from the raw request body. */
    static Map<String, Object> exchangeValues(ClaudeBackend backend, String requestBody) {
        Map<String, Object> body = asMap(backend.json.parse(requestBody != null ? requestBody : ""));
        String code = body != null ? stringOf(body.get("code")) : null;
        String state = body != null ? stringOf(body.get("state")) : null;
        return exchangeValues(backend, code, state);
    }

    static Map<String, Object> exchangeValues(ClaudeBackend backend, String code, String state) {
        String verifier = verifierFromState(state);
        if (verifier == null) {
            throw new IllegalStateException("missing PKCE verifier in state");
        }
        // Field set matches exchangeClaude()'s JSON.stringify body exactly, including sending
        // `state` back to the token endpoint (the TS does this even though it already decoded it).
        String body = "{"
                + "\"grant_type\":\"authorization_code\","
                + "\"client_id\":" + quote(CLIENT_ID) + ","
                + "\"code\":" + quote(code) + ","
                + "\"redirect_uri\":" + quote(REDIRECT_URI) + ","
                + "\"code_verifier\":" + quote(verifier) + ","
                + "\"state\":" + quote(state != null ? state : "")
                + "}";
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = TOKEN_URL;
        req.headers = new LinkedHashMap<>();
        req.headers.put("content-type", "application/json");
        req.body = body;

        HttpResponse resp;
        try {
            resp = backend.http.send(req);
        } catch (Exception e) {
            throw new IllegalStateException("claude token exchange failed");
        }
        if (resp.status / 100 != 2) {
            throw new IllegalStateException("claude token endpoint returned " + resp.status);
        }
        Map<String, Object> payload = asMap(backend.json.parse(resp.body));
        String refresh = payload != null ? stringOf(payload.get("refresh_token")) : null;
        if (refresh == null) {
            throw new IllegalStateException("missing refresh token in response");
        }
        String access = stringOf(payload.get("access_token"));
        long expires = calcExpiry(backend.clock.now(), payload.get("expires_in"));
        String email = emailFrom(payload);

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", email != null ? email : refresh);
        if (email != null) account.put("email", email);
        account.put("refresh", refresh);
        if (access != null) account.put("access", access);
        account.put("expires", expires);
        return Collections.<String, Object>singletonMap("account", account);
    }

    // --- helpers ---

    /** encodeState({verifier}) from oauth.ts: base64url(JSON.stringify({verifier}), "utf8"), no padding. */
    private static String encodeState(String verifier) {
        return URL64.encodeToString(("{\"verifier\":\"" + verifier + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** decodeState() from oauth.ts: base64url (padded back to standard base64) -> JSON -> .verifier. */
    private static String verifierFromState(String state) {
        if (state == null || state.isEmpty()) return null;
        try {
            String norm = state.replace('-', '+').replace('_', '/');
            int pad = (4 - (norm.length() % 4)) % 4;
            for (int i = 0; i < pad; i++) norm += "=";
            String jsonText = new String(Base64.getDecoder().decode(norm), StandardCharsets.UTF_8);
            int k = jsonText.indexOf("\"verifier\"");
            if (k < 0) return null;
            int q1 = jsonText.indexOf('"', jsonText.indexOf(':', k) + 1);
            int q2 = q1 >= 0 ? jsonText.indexOf('"', q1 + 1) : -1;
            return (q1 >= 0 && q2 > q1) ? jsonText.substring(q1 + 1, q2) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** calculateExpiry() from oauth.ts: only a positive numeric expires_in overrides the 3600s default. */
    private static long calcExpiry(long nowMs, Object expiresIn) {
        double seconds = expiresIn instanceof Number ? ((Number) expiresIn).doubleValue() : 3600;
        if (Double.isNaN(seconds) || seconds <= 0) seconds = 3600;
        return nowMs + (long) (seconds * 1000);
    }

    /** Email fallback order from exchangeClaude(): account.email_address -> account.email -> organization.name. */
    private static String emailFrom(Map<String, Object> payload) {
        Map<String, Object> account = asMap(payload.get("account"));
        if (account != null) {
            String e = stringOf(account.get("email_address"));
            if (e == null) e = stringOf(account.get("email"));
            if (e != null) return e;
        }
        Map<String, Object> org = asMap(payload.get("organization"));
        return org != null ? stringOf(org.get("name")) : null;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
