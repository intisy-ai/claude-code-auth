package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.QuotaBar;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed-capability parity test of {@link ClaudeProvider#quota} (quota-display Task 1, migrated to
 * the {@link io.github.intisy.ai.shared.routing.QuotaProvider} SPI in E-C -- {@code handle()} no
 * longer answers {@code GET /v1/quota} at all, so the typed method is called directly). Mirrors
 * {@link ClaudeModelsFetchTest}'s harness: a scripted {@link HttpClient} injected via {@link
 * ClaudeBackend#forTest}/{@link ClaudeBackend#registerForTest} so the real provider is driven
 * end-to-end without any real network call.
 */
class ClaudeUsageFetchTest {

    @Test
    void happyPath_returnsBarsAndPersistsCachedQuota(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"limits\":[" +
                        "{\"kind\":\"session\",\"percent\":18,\"resets_at\":\"2025-07-16T12:00:00Z\"}," +
                        "{\"kind\":\"weekly_all\",\"percent\":95,\"resets_at\":\"2025-07-20T00:00:00Z\"}" +
                        "]}");

        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acc1"));

        List<AccountQuota> accounts = handleQuota(configDir);

        assertEquals(1, accounts.size());
        AccountQuota entry = accounts.get(0);
        assertEquals("acc1", entry.accountId);
        assertEquals("acc1@example.com", entry.accountEmail);
        assertEquals("active", entry.accountStatus);

        assertEquals(2, entry.bars.size());
        assertEquals("5-hour", entry.bars.get(0).label);
        assertEquals(0.82, entry.bars.get(0).remainingFraction, 1e-9);
        assertEquals(isoOf("2025-07-16T12:00:00Z"), entry.bars.get(0).resetTime);
        assertEquals("7-day", entry.bars.get(1).label);
        assertEquals(0.05, entry.bars.get(1).remainingFraction, 1e-9);
        assertEquals(isoOf("2025-07-20T00:00:00Z"), entry.bars.get(1).resetTime);

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("GET", sent.method);
        assertEquals("https://api.anthropic.com/api/oauth/usage", sent.url);
        assertEquals("Bearer access-acc1", sent.headers.get("authorization"));
        assertEquals("2023-06-01", sent.headers.get("anthropic-version"));
        assertTrue(sent.headers.get("anthropic-beta").contains("oauth-2025-04-20"));

        // Persistence: re-reading the account store (backed by the real on-disk FileStore under
        // configDir) must show the pools now cached under meta.cachedQuota.
        Account persisted = findAccount(configDir, http, "acc1");
        assertTrue(persisted.meta != null && persisted.meta.get("cachedQuota") instanceof java.util.Map,
                "meta.cachedQuota must be persisted");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cachedQuota = (java.util.Map<String, Object>) persisted.meta.get("cachedQuota");
        assertTrue(cachedQuota.get("pools") instanceof java.util.Map, "meta.cachedQuota.pools must be persisted");
    }

    @Test
    void twoEnabledAccounts_returnsTwoEntries_eachPersisted(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"limits\":[{\"kind\":\"session\",\"percent\":10,\"resets_at\":\"2025-07-16T12:00:00Z\"}]}")
                .enqueueOk(200, "{\"limits\":[{\"kind\":\"session\",\"percent\":20,\"resets_at\":\"2025-07-16T13:00:00Z\"}]}");

        ClaudeBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acc1"));
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acc2"));

        List<AccountQuota> accounts = handleQuota(configDir);

        assertEquals(2, accounts.size());
        assertEquals(2, http.requests.size());

        for (String id : new String[] {"acc1", "acc2"}) {
            Account persisted = findAccount(configDir, http, id);
            assertTrue(persisted.meta != null && persisted.meta.get("cachedQuota") instanceof java.util.Map,
                    id + " must have persisted cachedQuota");
        }
    }

    @Test
    void zeroEnabledAccounts_returnsEmptyList_withoutCallingUpstream(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded at all -> zero enabled accounts.

        List<AccountQuota> accounts = handleQuota(configDir);

        assertTrue(accounts.isEmpty());
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with zero enabled accounts");
    }

    @Test
    void disabledAccountOnly_isSkipped_treatedAsZeroEnabled(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        ClaudeBackend backend = registerTestBackend(configDir, http);
        Account disabled = seededAccount("acct-disabled");
        disabled.enabled = false;
        backend.accountStore.add(ClaudeBackend.PROVIDER_ID, disabled);

        List<AccountQuota> accounts = handleQuota(configDir);

        assertTrue(accounts.isEmpty());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void upstream401_returnsErrorAccountQuota_notAThrow_stillOneEntry(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}");
        registerTestBackend(configDir, http).accountStore.add(ClaudeBackend.PROVIDER_ID, seededAccount("acc1"));

        List<AccountQuota> accounts = handleQuota(configDir);

        assertEquals(1, accounts.size());
        AccountQuota entry = accounts.get(0);
        assertEquals("acc1", entry.accountId);
        assertEquals("error", entry.accountStatus);
        assertTrue(entry.bars.isEmpty(), "an errored fetch has no pool bars, but the account is still represented");
    }

    // ---- shared fixtures (mirrors ClaudeModelsFetchTest) ----------------------------------------

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

    private static Account findAccount(Path configDir, HttpClient http, String id) {
        ClaudeBackend backend = ClaudeBackend.forTest(configDir.toString(), http);
        for (Account a : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (id.equals(a.id)) return a;
        }
        return null;
    }

    /** {@code QuotaBar.resetTime} is ISO-8601 now (re-encoded from the old raw epoch-ms number). */
    private static String isoOf(String iso) {
        return java.time.Instant.parse(iso).toString();
    }

    private static List<AccountQuota> handleQuota(Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        return new ClaudeProvider().quota(ctx);
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
