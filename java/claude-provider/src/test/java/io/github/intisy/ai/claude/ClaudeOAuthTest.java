package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.http.HttpResponse;
import io.github.intisy.ai.shared.spi.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeOAuthTest {

    @Test
    void configIsEmpty(@TempDir Path dir) {
        HttpResponse r = ClaudeOAuth.config();
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"groups\":[]"), r.body);
    }

    @Test
    void authorizeBuildsRealClaudeUrl(@TempDir Path dir) {
        HttpResponse r = ClaudeOAuth.authorize();
        assertEquals(200, r.status);
        assertTrue(r.body.contains("https://claude.ai/oauth/authorize"), r.body);
        assertTrue(r.body.contains("9d1c250a-e61b-44d9-88ed-5944d1962f5e"), r.body);
        assertTrue(r.body.contains("code_challenge="), r.body);
        assertTrue(r.body.contains("code_challenge_method=S256"), r.body);
        assertTrue(r.body.contains("state="), r.body);
        assertTrue(r.body.contains("\"completion\":\"paste\""), r.body);
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
        HttpResponse r = ClaudeOAuth.exchange(backend, "the-code", state);
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"refresh\":\"rt-1\""), r.body);
        assertTrue(r.body.contains("me@example.com"), r.body);
    }
}
