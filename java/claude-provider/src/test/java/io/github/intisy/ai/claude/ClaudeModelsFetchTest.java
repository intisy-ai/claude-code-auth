package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelInfo;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed-capability parity test of {@link ClaudeProvider#models} (model-map Task 1, migrated to the
 * {@link io.github.intisy.ai.shared.routing.ModelCatalogProvider} SPI in E-C -- the retired {@code
 * GET /v1/models} HttpResponse branch used to be exercised through {@code handle()}; the typed
 * method is called directly now, since {@code handle()} no longer answers that URL at all).
 * Mirrors {@link ClaudeProviderTest}'s harness: a scripted {@link HttpClient} injected via {@link
 * ClaudeBackend#forTest}/{@link ClaudeBackend#registerForTest} so the real provider is driven
 * end-to-end without any real network call.
 */
class ClaudeModelsFetchTest {

    @Test
    void happyPath_mapsUpstreamCatalogWithOAuthHeaders_noApiKey(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"data\":[" +
                        "{\"id\":\"claude-sonnet-4\",\"display_name\":\"Claude Sonnet 4\"}," +
                        "{\"id\":\"gpt-4\",\"display_name\":\"GPT-4\"}" +
                        "]}");

        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        List<ModelInfo> models = handleModels(configDir);

        assertEquals(1, models.size(), "non-claude ids must be filtered out");
        assertEquals("claude-sonnet-4", models.get(0).id);
        assertEquals("Claude Sonnet 4 (Claude Code)", models.get(0).name);

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("GET", sent.method);
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", sent.url);
        assertEquals("Bearer access-acct-a", sent.headers.get("authorization"));
        assertEquals("2023-06-01", sent.headers.get("anthropic-version"));
        assertTrue(sent.headers.get("anthropic-beta").contains("oauth-2025-04-20"));
        assertNull(sent.headers.get("x-api-key"), "no x-api-key must ever be sent for the OAuth/Bearer path");
    }

    @Test
    void noEnabledAccount_returnsEmptyList_withoutCallingUpstream(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded at all -> zero enabled accounts.

        List<ModelInfo> models = handleModels(configDir);

        assertTrue(models.isEmpty());
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no enabled account");
    }

    @Test
    void disabledAccountOnly_isSkipped_treatedAsNoAccount(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        ClaudeBackend backend = registerTestBackend(configDir, http);
        Account disabled = seededAccount("acct-disabled");
        disabled.enabled = false;
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, disabled);

        List<ModelInfo> models = handleModels(configDir);

        assertTrue(models.isEmpty());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void upstreamNon2xx_returnsEmptyList_notAThrow(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acct-a"));

        List<ModelInfo> models = handleModels(configDir);

        assertTrue(models.isEmpty(), "upstream failure must fold into an empty list, not throw");
    }

    // ---- shared fixtures (mirrors ClaudeProviderTest) -------------------------------------------

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

    private static List<ModelInfo> handleModels(Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        return new ClaudeProvider().models(ctx);
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
