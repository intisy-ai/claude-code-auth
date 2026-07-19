package io.github.intisy.ai.claude;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrMessage;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandleIrException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of {@link ClaudeProvider#handleIr}: drives the REAL provider with a scripted
 * {@link HttpClient} injected into the backend via {@link ClaudeBackend#forTest}/{@link
 * ClaudeBackend#registerForTest} (mirrors antigravity-auth's {@code AntigravityServePathTest}),
 * so the orchestrator's retry/rotation/synthetic-response behavior is exercised end-to-end
 * without any real network call. The provider is IR-native only (T4): a 2xx serve returns an
 * {@link IrResponse}; every non-2xx or synthetic outcome throws {@link HandleIrException}.
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
    void happyPath_stampsOAuthHeadersOnUpstreamRequest(@TempDir Path configDir) throws Exception {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-code-sonnet\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        IrResponse response = new ClaudeProvider().handleIr(sampleIrRequest(), ctxFor(configDir));

        assertEquals("msg_1", response.id);

        // The IR-native path builds bodyText from the IrRequest, then prepareClaudeRequest stamps
        // the same Claude-Code OAuth identity on the outbound upstream request as before.
        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", sent.url);
        assertEquals("Bearer access-acct-a", sent.headers.get("authorization"));
        assertEquals("2023-06-01", sent.headers.get("anthropic-version"));
        assertTrue(sent.headers.get("anthropic-beta").contains("oauth-2025-04-20"));
    }

    @Test
    void rateLimitRotatesToNextAccount(@TempDir Path configDir) throws Exception {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")
                .enqueueOk(200, "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-code-sonnet\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-b"));

        IrResponse response = new ClaudeProvider().handleIr(sampleIrRequest(), ctxFor(configDir));

        assertEquals("msg_2", response.id);
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
    void noAccountConfigured_throwsSyntheticInvalidRequestError(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded -> listEnabledCount() == 0.

        HandleIrException thrown = assertThrows(HandleIrException.class,
                () -> new ClaudeProvider().handleIr(sampleIrRequest(), ctxFor(configDir)));

        assertEquals(400, thrown.status);
        assertEquals("1", thrown.headers.get("x-hub-chat-error"));
        assertTrue(thrown.body.contains("invalid_request_error"));
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no account");
    }

    @Test
    void allAttemptsRateLimited_throwsLastRealUpstreamResponse(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"exhausted\"}}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-solo")); // maxAttempts == 1

        HandleIrException thrown = assertThrows(HandleIrException.class,
                () -> new ClaudeProvider().handleIr(sampleIrRequest(), ctxFor(configDir)));

        assertEquals(429, thrown.status);
        assertTrue(thrown.body.contains("exhausted"));
        assertEquals(1, http.requests.size());
    }

    @Test
    void servesFromInjectedStore_notFileStore() throws Exception {
        MapStore store = new MapStore();
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_s\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-code-sonnet\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        ClaudeBackend backend = ClaudeBackend.forTest(store, http);
        ClaudeBackend.registerForTest(store, backend);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-s"));

        HandlerCtx ctx = new HandlerCtx();
        ctx.store = store; // NO configDir -- must serve from the injected store, not FileStore

        IrResponse response = new ClaudeProvider().handleIr(sampleIrRequest(), ctx);

        assertEquals("msg_s", response.id);
        assertEquals(1, http.requests.size());
        // the seeded account must live in the INJECTED store (single source of truth)
        boolean inStore = false;
        for (String k : store.listKeys(null)) {
            String v = store.get(k);
            if (v != null && v.contains("acct-s")) { inStore = true; break; }
        }
        assertTrue(inStore, "the account must live in the injected store, proving the provider used it");
    }

    // ---- SP-3 T2: handleIr ---------------------------------------------------------------------

    @Test
    void handleIr_happyPath_decodesUpstreamMessageIntoIrResponse(@TempDir Path configDir) throws Exception {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-code-sonnet\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        IrResponse response = new ClaudeProvider().handleIr(sampleIrRequest(), ctx);

        assertEquals("msg_1", response.id);
        assertEquals("end_turn", response.stopReason);
        assertEquals(1, response.content.size());
        assertTrue(response.content.get(0) instanceof TextBlock);
        assertEquals("hi", ((TextBlock) response.content.get(0)).text);
        assertEquals(1, http.requests.size());
        // The IR-native path builds its own bodyText from the IrRequest -- prepareClaudeRequest
        // still stamps the Claude-Code identity system block and OAuth headers, exactly as handle().
        assertTrue(http.requests.get(0).headers.get("authorization").startsWith("Bearer "));
    }

    @Test
    void handleIr_nonTwoxxUpstream_throwsHandleIrExceptionWithRealStatusAndBody(@TempDir Path configDir) {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"exhausted\"}}";
        ScriptedHttpClient http = new ScriptedHttpClient().enqueueError(429, body);
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-solo"));
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        // T3c-2: a non-2xx upstream response is not Anthropic MESSAGE-shaped JSON and must not be
        // coerced through decodeResponse -- handleIr throws the canonical HandleIrException instead,
        // carrying the real upstream status/body verbatim (so core-proxy's front door can restore
        // status fidelity instead of collapsing to a flat 502).
        HandleIrException thrown = assertThrows(HandleIrException.class,
                () -> new ClaudeProvider().handleIr(sampleIrRequest(), ctx));
        assertEquals(429, thrown.status);
        assertEquals(body, thrown.body);
    }

    @Test
    void handleIr_noAccountConfigured_throwsHandleIrExceptionWithSyntheticStatusAndBody(
            @TempDir Path configDir) {
        registerTestBackend(configDir, new ScriptedHttpClient());
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        // T3c-2: a SYNTHETIC decision's body (no-account/exhaustion) is not guaranteed to be
        // Anthropic MESSAGE-shaped JSON and must not be coerced through decodeResponse -- handleIr
        // throws the canonical HandleIrException, carrying this provider's own synthesized
        // status/body through unchanged.
        HandleIrException thrown = assertThrows(HandleIrException.class,
                () -> new ClaudeProvider().handleIr(sampleIrRequest(), ctx));
        assertTrue(thrown.status >= 400);
        assertTrue(thrown.body != null && !thrown.body.isEmpty());
    }

    private static IrRequest sampleIrRequest() {
        IrRequest ir = new IrRequest();
        ir.model = "claude-code-sonnet";
        ir.stream = false;
        List<Block> content = new ArrayList<>();
        content.add(new TextBlock("hi"));
        List<IrMessage> messages = new ArrayList<>();
        messages.add(new IrMessage("user", content));
        ir.messages = messages;
        return ir;
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

    private static HandlerCtx ctxFor(Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = null;
        return ctx;
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
