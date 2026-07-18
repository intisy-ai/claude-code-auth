package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelCatalogProvider;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.routing.OAuthProvider;
import io.github.intisy.ai.shared.routing.QuotaProvider;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void id_isClaudeCodeAuth() {
        assertEquals("claude-code-auth", new ClaudeProvider().id());
    }

    @Test
    void implementsAllFourTypedCapabilities() {
        ClaudeProvider provider = new ClaudeProvider();
        assertInstanceOf(ConfigurableProvider.class, provider);
        assertInstanceOf(ModelCatalogProvider.class, provider);
        assertInstanceOf(QuotaProvider.class, provider);
        assertInstanceOf(OAuthProvider.class, provider);
    }

    @Test
    void typedConfigCapability_delegatesToClaudeConfig(@TempDir Path configDir) {
        ClaudeProvider provider = new ClaudeProvider();
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        ConfigSchema schema = provider.configSchema(ctx);
        assertEquals(1, schema.groups.size());
        assertEquals("Claude", schema.groups.get(0).title);

        Map<String, Object> values = provider.getConfigValues(ctx);
        assertEquals(Boolean.TRUE, values.get("logging"));

        Map<String, Object> updated = provider.putConfigValues(ctx, Map.of("logging", Boolean.FALSE));
        assertEquals(Boolean.FALSE, updated.get("logging"));
    }

    @Test
    void typedModelCatalogCapability_delegatesToClaudeModelsFetch(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"data\":[{\"id\":\"claude-sonnet-4\",\"display_name\":\"Claude Sonnet 4\"}]}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        List<ModelInfo> models = new ClaudeProvider().models(ctx);
        assertEquals(1, models.size());
        assertEquals("claude-sonnet-4", models.get(0).id);
    }

    @Test
    void typedQuotaCapability_delegatesToClaudeUsageFetch(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"limits\":[{\"kind\":\"session\",\"percent\":10,\"resets_at\":\"2025-07-16T12:00:00Z\"}]}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        List<AccountQuota> accounts = new ClaudeProvider().quota(ctx);
        assertEquals(1, accounts.size());
        assertEquals("acct-a", accounts.get(0).accountId);
        assertEquals(1, accounts.get(0).bars.size());
    }

    @Test
    void typedOAuthCapability_delegatesToClaudeOAuth(@TempDir Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        AuthorizeInfo info = new ClaudeProvider().authorize(ctx);
        assertEquals("paste", info.completion);
        assertTrue(info.authorizeUrl.contains("https://claude.ai/oauth/authorize"));
    }

    @Test
    void handle_noLongerAnswersConfigOrOAuthUrls_branchesRetired(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}")
                .enqueueOk(200, "{\"id\":\"msg_2\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        // Every one of these used to be a dedicated URL branch in handle() -- now they all fall
        // through to the SAME messages orchestrator passthrough (proving the branches are gone,
        // not that the resulting behavior is sensible for these URLs).
        HttpRequest configReq = new HttpRequest();
        configReq.method = "GET";
        configReq.url = "/v1/config";
        HttpResponse configResp = new ClaudeProvider().handle(configReq, ctx);
        assertEquals(200, configResp.status);
        assertEquals("https://api.anthropic.com/v1/config", http.requests.get(0).url);

        HttpRequest authorizeReq = new HttpRequest();
        authorizeReq.method = "GET";
        authorizeReq.url = "/v1/oauth/authorize";
        HttpResponse authorizeResp = new ClaudeProvider().handle(authorizeReq, ctx);
        assertEquals(200, authorizeResp.status);
        assertEquals("https://api.anthropic.com/v1/oauth/authorize", http.requests.get(1).url);
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

    @Test
    void servesFromInjectedStore_notFileStore() {
        MapStore store = new MapStore();
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_s\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}");
        ClaudeBackend backend = ClaudeBackend.forTest(store, http);
        ClaudeBackend.registerForTest(store, backend);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-s"));

        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"claude-code-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        HandlerCtx ctx = new HandlerCtx();
        ctx.store = store; // NO configDir -- must serve from the injected store, not FileStore

        HttpResponse response = new ClaudeProvider().handle(request, ctx);

        assertEquals(200, response.status, response.body);
        assertEquals(1, http.requests.size());
        // the seeded account must live in the INJECTED store (single source of truth)
        boolean inStore = false;
        for (String k : store.listKeys(null)) {
            String v = store.get(k);
            if (v != null && v.contains("acct-s")) { inStore = true; break; }
        }
        assertTrue(inStore, "the account must live in the injected store, proving the provider used it");
    }

    /** Minimal in-memory {@link io.github.intisy.ai.shared.spi.Store} test double (6-method SPI). */
    private static final class MapStore implements io.github.intisy.ai.shared.spi.Store {
        private final java.util.Map<String, String> m = new java.util.concurrent.ConcurrentHashMap<>();
        public String get(String k) { return m.get(k); }
        public void put(String k, String v) { m.put(k, v); }
        public boolean exists(String k) { return m.containsKey(k); }
        public void delete(String k) { m.remove(k); }
        public synchronized void update(String k, java.util.function.UnaryOperator<String> f) {
            String nv = f.apply(m.get(k));
            if (nv == null) m.remove(k); else m.put(k, nv);
        }
        public java.util.List<String> listKeys(String prefix) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String k : m.keySet()) if (prefix == null || k.startsWith(prefix)) out.add(k);
            return out;
        }
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
