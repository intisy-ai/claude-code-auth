package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.QuotaBar;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 * dashboard has a durable cache even between live fetches. {@link #quota} never throws: a failure
 * fetching one account's usage folds into an {@code AccountQuota} with {@code accountStatus =
 * "error"} and empty {@code bars} for that account only -- it never aborts the whole list, and the
 * account itself is still represented (matching {@link AccountQuota}'s own preserve-bar-less-
 * accounts contract).
 */
final class ClaudeUsageFetch {

    private static final String USAGE_PATH = "/api/oauth/usage";

    private ClaudeUsageFetch() {
    }

    /** {@link io.github.intisy.ai.shared.routing.QuotaProvider#quota}. */
    static List<AccountQuota> quota(ClaudeBackend backend) {
        List<AccountQuota> out = new ArrayList<>();
        for (Account account : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (account.enabled == Boolean.FALSE) continue;
            out.add(accountQuotaFor(backend, account));
        }
        return out;
    }

    // Never rethrows -- any failure for THIS account (refresh, network, non-2xx, malformed body)
    // folds into an error AccountQuota so one bad account never breaks the whole quota() call.
    private static AccountQuota accountQuotaFor(ClaudeBackend backend, Account account) {
        try {
            return doAccountQuotaFor(backend, account);
        } catch (Throwable e) {
            return errorAccountQuota(account);
        }
    }

    private static AccountQuota doAccountQuotaFor(ClaudeBackend backend, Account account) {
        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            return errorAccountQuota(account);
        }
        if (access == null || access.trim().isEmpty()) {
            return errorAccountQuota(account);
        }

        HttpResponse resp;
        try {
            resp = backend.http.send(buildRequest(access));
        } catch (RuntimeException e) {
            return errorAccountQuota(account);
        }
        if (resp.status / 100 != 2) {
            return errorAccountQuota(account);
        }

        Map<String, Object> pools = parsePools(backend, resp.body);
        if (pools == null) {
            return errorAccountQuota(account);
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

        List<QuotaBar> bars = new ArrayList<>();
        if (quota != null) {
            for (Map<String, Object> bar : quota) {
                bars.add(new QuotaBar(
                        (String) bar.get("label"),
                        fractionOf(bar.get("remainingFraction")),
                        isoResetTime(bar.get("resetTime"))));
            }
        }
        return new AccountQuota(account.id, account.email, active ? "active" : "rate-limited", bars);
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

    private static AccountQuota errorAccountQuota(Account account) {
        return new AccountQuota(account.id, account.email, "error", Collections.<QuotaBar>emptyList());
    }

    private static double fractionOf(Object remainingFraction) {
        return remainingFraction instanceof Number ? ((Number) remainingFraction).doubleValue() : 0.0;
    }

    // The pre-migration wire shape carried resetTime as a raw epoch-ms number (see epochMs()
    // below); QuotaBar.resetTime is a String on the typed SPI, so re-encode the SAME instant as
    // ISO-8601 rather than dropping it -- a lossless format change, not a dropped field.
    private static String isoResetTime(Object resetTimeEpochMs) {
        return resetTimeEpochMs instanceof Number
                ? Instant.ofEpochMilli(((Number) resetTimeEpochMs).longValue()).toString()
                : null;
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
