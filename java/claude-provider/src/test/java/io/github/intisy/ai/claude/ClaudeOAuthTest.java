package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed-capability parity test of {@link ClaudeOAuth#authorizeInfo()}/{@link
 * ClaudeOAuth#exchangeValues}, the typed {@link io.github.intisy.ai.shared.routing.OAuthProvider}
 * entry points.
 */
class ClaudeOAuthTest {

    @Test
    void authorizeBuildsRealClaudeUrl(@TempDir Path dir) {
        AuthorizeInfo info = ClaudeOAuth.authorizeInfo();
        assertEquals("paste", info.completion);
        assertTrue(info.authorizeUrl.contains("https://claude.ai/oauth/authorize"), info.authorizeUrl);
        assertTrue(info.authorizeUrl.contains("9d1c250a-e61b-44d9-88ed-5944d1962f5e"), info.authorizeUrl);
        assertTrue(info.authorizeUrl.contains("code_challenge="), info.authorizeUrl);
        assertTrue(info.authorizeUrl.contains("code_challenge_method=S256"), info.authorizeUrl);
        assertTrue(info.authorizeUrl.contains("state="), info.authorizeUrl);
        assertTrue(info.state != null && !info.state.isEmpty(), "state must be populated");
        assertNull(info.loopbackPort);
        assertNull(info.loopbackPath);
    }

    @Test
    void exchangeParsesTokensAndEmail(@TempDir Path dir) {
        HttpClient http = req -> {
            HttpResponse resp = new HttpResponse();
            resp.status = 200;
            resp.headers = new LinkedHashMap<>();
            resp.body = "{\"refresh_token\":\"rt-1\",\"access_token\":\"at-1\",\"expires_in\":3600,"
                    + "\"account\":{\"email_address\":\"me@example.com\"}}";
            return resp;
        };
        ClaudeBackend backend = ClaudeBackend.forTest(dir.toString(), http);
        String state = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"verifier\":\"the-verifier\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map<String, Object> result = ClaudeOAuth.exchangeValues(backend, "the-code", state);

        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) result.get("account");
        assertEquals("rt-1", account.get("refresh"));
        assertEquals("me@example.com", account.get("email"));
        assertEquals("me@example.com", account.get("id"));
        assertEquals("at-1", account.get("access"));
    }

    @Test
    void exchangeMissingVerifier_throws(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forTest(dir.toString(), req -> {
            throw new IllegalStateException("must not call upstream without a verifier");
        });

        assertThrows(IllegalStateException.class, () -> ClaudeOAuth.exchangeValues(backend, "the-code", null));
    }

    @Test
    void exchangeMissingRefreshToken_throws(@TempDir Path dir) {
        HttpClient http = req -> {
            HttpResponse resp = new HttpResponse();
            resp.status = 200;
            resp.headers = new LinkedHashMap<>();
            resp.body = "{\"access_token\":\"at-1\"}"; // no refresh_token
            return resp;
        };
        ClaudeBackend backend = ClaudeBackend.forTest(dir.toString(), http);
        String state = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"verifier\":\"the-verifier\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> ClaudeOAuth.exchangeValues(backend, "the-code", state));
    }
}
