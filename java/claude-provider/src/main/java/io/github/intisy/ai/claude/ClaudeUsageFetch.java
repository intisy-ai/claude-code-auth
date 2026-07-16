package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quota-display Task 1: {@code GET /v1/quota} per-account usage fetch for the example-server
 * dashboard. Java port of the HOST-I/O half of claude-code-auth's TS {@code fetchUsagePools} (see
 * {@code src/driver/accounts-controller.ts:71-89}) -- the bucketing/labeling half is already
 * ported as {@link ClaudeQuotaParser}, which this class feeds with the raw upstream limits.
 *
 * <p>Unlike {@link ClaudeModelsFetch} (single first-enabled-account discovery), this fetches usage
 * for EVERY enabled account and persists each one's pools to {@code meta.cachedQuota} so the
 * dashboard has a durable cache even between live fetches. Never throws: a failure fetching one
 * account's usage folds into an {@code error} entry for that account only -- it never aborts the
 * whole response, matching the "one account fails" test`s "still 200 overall" contract.
 */
final class ClaudeUsageFetch {

    private static final String USAGE_PATH = "/api/oauth/usage";

    private ClaudeUsageFetch() {
    }

    static HttpResponse fetch(ClaudeBackend backend, HandlerCtx ctx) {
        try {
            return doFetch(backend);
        } catch (Throwable e) {
            // Never throw out of a provider handle() path -- any unexpected failure folds into
            // the same api_error shape an upstream failure would. Never include e.getMessage()
            // here: it could echo back a header/token fragment from a lower-level failure.
            return ClaudeProvider.errorResponse(502, "api_error", "quota fetch failed");
        }
    }

    private static HttpResponse doFetch(ClaudeBackend backend) {
        List<Object> entries = new ArrayList<>();
        for (Account account : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (account.enabled == Boolean.FALSE) continue;
            entries.add(entryFor(backend, account));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", entries);

        HttpResponse out = new HttpResponse();
        out.status = 200;
        out.headers = new LinkedHashMap<>();
        out.headers.put("content-type", "application/json");
        out.body = backend.json.stringify(body);
        return out;
    }

    // Never rethrows -- any failure for THIS account (refresh, network, non-2xx, malformed body)
    // folds into an error entry so one bad account never breaks the whole /v1/quota response.
    private static Map<String, Object> entryFor(ClaudeBackend backend, Account account) {
        try {
            return doEntryFor(backend, account);
        } catch (Throwable e) {
            return errorEntry(account);
        }
    }

    private static Map<String, Object> doEntryFor(ClaudeBackend backend, Account account) {
        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            return errorEntry(account);
        }
        if (access == null || access.trim().isEmpty()) {
            return errorEntry(account);
        }

        HttpResponse resp;
        try {
            resp = backend.http.send(buildRequest(access));
        } catch (RuntimeException e) {
            return errorEntry(account);
        }
        if (resp.status / 100 != 2) {
            return errorEntry(account);
        }

        Map<String, Object> pools = parsePools(backend, resp.body);
        if (pools == null) {
            return errorEntry(account);
        }

        persistCachedQuota(backend, account.id, pools);

        // Feed the just-parsed pools back through ClaudeQuotaParser's own cached-quota shape
        // (reuse its bucketing/labeling/clamping instead of reinventing it here).
        Map<String, Object> synth = new LinkedHashMap<>();
        Map<String, Object> cachedQuota = new LinkedHashMap<>();
        cachedQuota.put("pools", pools);
        synth.put("cachedQuota", cachedQuota);

        List<Map<String, Object>> quota = ClaudeQuotaParser.claudeQuota(synth);
        boolean active = ClaudeQuotaParser.accountHasQuota(synth);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", account.id);
        if (account.email != null) entry.put("email", account.email);
        entry.put("status", active ? "active" : "rate-limited");
        entry.put("quota", quota);
        return entry;
    }

    private static void persistCachedQuota(ClaudeBackend backend, String accountId, Map<String, Object> pools) {
        backend.accounts.mutate(accountId, acc -> {
            if (acc.meta == null) acc.meta = new LinkedHashMap<>();
            Map<String, Object> cq = new LinkedHashMap<>();
            cq.put("pools", pools);
            cq.put("at", System.currentTimeMillis());
            acc.meta.put("cachedQuota", cq);
        });
    }

    private static Map<String, Object> errorEntry(Account account) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", account.id);
        if (account.email != null) entry.put("email", account.email);
        entry.put("status", "error");
        entry.put("quota", null);
        return entry;
    }

    private static HttpRequest buildRequest(String access) {
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = AnthropicRequestTranslator.ANTHROPIC_API_BASE + USAGE_PATH;
        req.headers = new LinkedHashMap<>();
        req.headers.put("authorization", "Bearer " + access);
        req.headers.put("anthropic-version", AnthropicRequestTranslator.ANTHROPIC_VERSION);
        req.headers.put("anthropic-beta", AnthropicRequestTranslator.ANTHROPIC_OAUTH_BETA);
        return req;
    }

    // TS fetchUsagePools body.limits[] loop (accounts-controller.ts:71-89): bucket a limit via
    // ClaudeQuotaParser.bucketOfLimit, skip anything without a bucket or a numeric percent.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePools(ClaudeBackend backend, String body) {
        Object parsed;
        try {
            parsed = backend.json.parse(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map)) return null;
        Object limitsObj = ((Map<String, Object>) parsed).get("limits");
        if (!(limitsObj instanceof List)) return null;

        Map<String, Object> pools = new LinkedHashMap<>();
        for (Object o : (List<Object>) limitsObj) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> limit = (Map<String, Object>) o;
            String bucket = ClaudeQuotaParser.bucketOfLimit(limit);
            Object percentObj = limit.get("percent");
            if (bucket == null || !(percentObj instanceof Number)) continue;

            Map<String, Object> pool = new LinkedHashMap<>();
            pool.put("utilization", ((Number) percentObj).doubleValue() / 100.0);
            pool.put("reset", epochMs(limit.get("resets_at")));
            Object severity = limit.get("severity");
            if (severity instanceof String && !((String) severity).isEmpty()) {
                pool.put("status", severity);
            }
            pools.put(bucket, pool);
        }
        return pools;
    }

    // resets_at is an ISO-8601 string; Instant.parse handles the common "Z"/offset form, falling
    // back to OffsetDateTime.parse for shapes Instant.parse rejects (e.g. an explicit +HH:MM
    // offset with no "Z"); null on any parse failure or a non-string/blank input.
    private static Long epochMs(Object resetsAt) {
        if (!(resetsAt instanceof String) || ((String) resetsAt).isEmpty()) return null;
        String s = (String) resetsAt;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e1) {
            try {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
