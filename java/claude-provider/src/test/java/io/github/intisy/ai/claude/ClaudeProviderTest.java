package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of {@link ClaudeProvider#handle}: drives the REAL provider with a scripted
 * {@link HttpClient} injected into the backend via {@link ClaudeBackend#forTest}/{@link
 * ClaudeBackend#registerForTest} (mirrors antigravity-auth's {@code AntigravityServePathTest}),
 * so the orchestrator's retry/rotation/synthetic-response materialization is exercised
 * end-to-end without any real network call.
 */
class ClaudeProviderTest {

    @Test
    void id_isClaude() {
        assertEquals("claude", new ClaudeProvider().id());
    }

    @Test
    void happyPath_returnsUpstreamBodyVerbatimWithOAuthHeaders(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        // Inbound request carries an x-api-key -- prepareClaudeRequest must strip it before the
        // outbound call, since Claude auth rides the Bearer/OAuth headers instead.
        HttpResponse response = handle(configDir, "x-api-key", "sk-should-be-stripped");

        assertEquals(200, response.status);
        assertEquals("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}", response.body);

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", sent.url);
        assertEquals("Bearer access-acct-a", sent.headers.get("authorization"));
        assertEquals("2023-06-01", sent.headers.get("anthropic-version"));
        assertTrue(sent.headers.get("anthropic-beta").contains("oauth-2025-04-20"));
        assertNull(sent.headers.get("x-api-key"), "the inbound x-api-key must be stripped, not forwarded upstream");
    }

    @Test
    void rateLimitRotatesToNextAccount(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")
                .enqueueOk(200, "{\"id\":\"msg_2\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-b"));

        HttpResponse response = handle(configDir);

        assertEquals(200, response.status);
        assertEquals(2, http.requests.size());
        String firstAuth = http.requests.get(0).headers.get("authorization");
        String secondAuth = http.requests.get(1).headers.get("authorization");
        assertNotEquals(firstAuth, secondAuth, "the rotation must have used a different account's token");

        // Identify the account that actually received the 429 (the one whose Bearer token matches
        // the FIRST outbound request -- seededAccount sets access = "access-" + id), then assert
        // the rate limit was recorded for THAT account specifically, not just "some" account.
        String firstAccountId = firstAuth.substring("Bearer access-".length());
        assertTrue(hasRateLimitEntry(backend, firstAccountId),
                "reportRateLimit must have been recorded for " + firstAccountId + ", the account that received the 429");
    }

    @Test
    void noAccountConfigured_returnsSyntheticInvalidRequestError(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded -> listEnabledCount() == 0.

        HttpResponse response = handle(configDir);

        assertEquals(400, response.status);
        assertEquals("1", response.headers.get("x-hub-chat-error"));
        assertTrue(response.body.contains("invalid_request_error"));
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no account");
    }

    @Test
    void allAttemptsRateLimited_servesLastRealUpstreamResponseVerbatim(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"exhausted\"}}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-solo")); // maxAttempts == 1

        HttpResponse response = handle(configDir);

        assertEquals(429, response.status);
        assertTrue(response.body.contains("exhausted"));
        assertEquals(1, http.requests.size());
    }

    // ---- shared fixtures -----------------------------------------------------------------------

    private static ClaudeBackend registerTestBackend(Path configDir, HttpClient http) {
        ClaudeBackend backend = ClaudeBackend.forTest(configDir.toString(), http);
        ClaudeBackend.registerForTest(configDir.toString(), backend);
        return backend;
    }

    private static Account seededAccount(String id) {
        Account a = new Account();
        a.id = id;
        a.email = id + "@example.com";
        a.refresh = "rt-" + id;
        a.access = "access-" + id; // fresh token -> AccountManager.ensureAccess never refreshes
        a.expires = System.currentTimeMillis() + 3_600_000L;
        a.enabled = true;
        return a;
    }

    private static boolean hasRateLimitEntry(ClaudeBackend backend, String accountId) {
        for (Account a : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (accountId.equals(a.id)) {
                return a.rateLimitResetTimes != null && !a.rateLimitResetTimes.isEmpty();
            }
        }
        return false;
    }

    private static HttpResponse handle(Path configDir) {
        return handle(configDir, (String[]) null);
    }

    /** {@code headerKv} is optional alternating key/value pairs set as the inbound request's headers. */
    private static HttpResponse handle(Path configDir, String... headerKv) {
        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"claude-code-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        if (headerKv != null) {
            request.headers = new LinkedHashMap<>();
            for (int i = 0; i < headerKv.length; i += 2) {
                request.headers.put(headerKv[i], headerKv[i + 1]);
            }
        }

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = null;

        return new ClaudeProvider().handle(request, ctx);
    }

    /** Scripted {@link HttpClient}: pops one queued response per {@link #send}, records every request. */
    private static final class ScriptedHttpClient implements HttpClient {
        private final Deque<HttpResponse> queue = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        ScriptedHttpClient enqueueOk(int status, String body) {
            return enqueue(status, body);
        }

        ScriptedHttpClient enqueueError(int status, String body) {
            return enqueue(status, body);
        }

        private ScriptedHttpClient enqueue(int status, String body) {
            HttpResponse r = new HttpResponse();
            r.status = status;
            r.headers = new LinkedHashMap<>();
            r.headers.put("content-type", "application/json");
            r.body = body;
            queue.add(r);
            return this;
        }

        @Override
        public HttpResponse send(HttpRequest req) {
            requests.add(req);
            HttpResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("ScriptedHttpClient queue empty for " + req.url);
            }
            return r;
        }
    }
}
