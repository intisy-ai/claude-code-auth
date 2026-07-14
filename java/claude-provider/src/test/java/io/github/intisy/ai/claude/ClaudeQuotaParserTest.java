package io.github.intisy.ai.claude;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.claude.Fixtures.list;
import static io.github.intisy.ai.claude.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic parity tests for {@link ClaudeQuotaParser}, checked against
 * claude-code-auth's actual {@code src/driver/accounts-controller.ts} behavior: the TS's pure
 * functions were extracted verbatim into a throwaway Node harness and executed with {@code node}
 * (v26.3.1) to snapshot the exact expected values used below -- not hand-derived from reading the
 * source alone. See task-6a-report.md for the harness.
 */
class ClaudeQuotaParserTest {

    private final ClaudeQuotaParser parser = new ClaudeQuotaParser(new TestJsonCodec());

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---- readPools ---------------------------------------------------------------------------

    @Test
    void readPools_normalTriple_capturesAllThreeFields() {
        Map<String, String> h = headers(
                "Anthropic-Ratelimit-Unified-5h-Utilization", "0.42", // mixed case header name
                "anthropic-ratelimit-unified-5h-reset", "1720000000",
                "anthropic-ratelimit-unified-5h-status", "allowed");
        Map<String, Map<String, Object>> pools = ClaudeQuotaParser.readPools(h);
        assertEquals(1, pools.size());
        Map<String, Object> pool = pools.get("5h");
        assertEquals(0.42, (Double) pool.get("utilization"), 1e-9);
        assertEquals(1720000000000L, pool.get("reset"));
        assertEquals("allowed", pool.get("status"));
    }

    @Test
    void readPools_bucketlessUnifiedResetHeader_ignored() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-reset", "123");
        assertTrue(ClaudeQuotaParser.readPools(h).isEmpty());
    }

    @Test
    void readPools_statusOnly_poolDroppedEntirely_noUtilizationOrReset() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-7d-status", "allowed");
        assertTrue(ClaudeQuotaParser.readPools(h).isEmpty());
    }

    @Test
    void readPools_garbageUtilization_poolSurvivesViaReset() {
        Map<String, String> h = headers(
                "anthropic-ratelimit-unified-7d-utilization", "notanumber",
                "anthropic-ratelimit-unified-7d-reset", "1000");
        Map<String, Map<String, Object>> pools = ClaudeQuotaParser.readPools(h);
        assertEquals(1, pools.size());
        Map<String, Object> pool = pools.get("7d");
        assertFalse(pool.containsKey("utilization"));
        assertEquals(1000000L, pool.get("reset"));
    }

    @Test
    void readPools_multiplePoolsIncludingModelScoped() {
        Map<String, String> h = headers(
                "anthropic-ratelimit-unified-5h-utilization", "0.1",
                "anthropic-ratelimit-unified-7d-utilization", "0.9",
                "anthropic-ratelimit-unified-7d-fable-utilization", "1.0");
        Map<String, Map<String, Object>> pools = ClaudeQuotaParser.readPools(h);
        assertEquals(0.1, (Double) pools.get("5h").get("utilization"), 1e-9);
        assertEquals(0.9, (Double) pools.get("7d").get("utilization"), 1e-9);
        assertEquals(1.0, (Double) pools.get("7d-fable").get("utilization"), 1e-9);
    }

    @Test
    void readPools_parseFloatAndParseIntIgnoreTrailingGarbage_matchesJsSemantics() {
        // parseFloat("0.5xyz") === 0.5 and parseInt("100abc", 10) === 100 in JS -- trailing
        // garbage after a valid leading number is ignored (unlike Number(), used elsewhere).
        Map<String, String> h = headers(
                "anthropic-ratelimit-unified-5h-utilization", "0.5xyz",
                "anthropic-ratelimit-unified-5h-reset", "100abc");
        Map<String, Map<String, Object>> pools = ClaudeQuotaParser.readPools(h);
        Map<String, Object> pool = pools.get("5h");
        assertEquals(0.5, (Double) pool.get("utilization"), 1e-9);
        assertEquals(100000L, pool.get("reset"));
    }

    // ---- bucketOfLimit -------------------------------------------------------------------------

    @Test
    void bucketOfLimit_sessionKind_isFiveHour() {
        assertEquals("5h", ClaudeQuotaParser.bucketOfLimit(map("kind", "session")));
    }

    @Test
    void bucketOfLimit_weeklyAllKind_isSevenDay() {
        assertEquals("7d", ClaudeQuotaParser.bucketOfLimit(map("kind", "weekly_all")));
    }

    @Test
    void bucketOfLimit_weeklyGroupWithDisplayNameScope_prefersDisplayNameOverId() {
        Map<String, Object> limit = map("group", "weekly",
                "scope", map("model", map("display_name", "Fable", "id", "claude-fable")));
        assertEquals("7d-fable", ClaudeQuotaParser.bucketOfLimit(limit));
    }

    @Test
    void bucketOfLimit_weeklyGroupWithIdOnlyScope_fallsBackToId() {
        Map<String, Object> limit = map("group", "weekly", "scope", map("model", map("id", "claude-fable")));
        assertEquals("7d-claude-fable", ClaudeQuotaParser.bucketOfLimit(limit));
    }

    @Test
    void bucketOfLimit_weeklyGroupNoScope_returnsBareGroup() {
        assertEquals("weekly", ClaudeQuotaParser.bucketOfLimit(map("group", "weekly")));
    }

    @Test
    void bucketOfLimit_unrecognizedKind_passesThrough() {
        assertEquals("custom_x", ClaudeQuotaParser.bucketOfLimit(map("kind", "custom_x")));
    }

    @Test
    void bucketOfLimit_noKindOrGroup_returnsNull() {
        assertNull(ClaudeQuotaParser.bucketOfLimit(map()));
    }

    @Test
    void bucketOfLimit_nullLimit_returnsNull() {
        assertNull(ClaudeQuotaParser.bucketOfLimit(null));
    }

    @Test
    void bucketOfLimit_multiWordScope_spacesBecomeDashes() {
        Map<String, Object> limit = map("group", "weekly", "scope", map("model", map("display_name", "Fable Pro")));
        assertEquals("7d-fable-pro", ClaudeQuotaParser.bucketOfLimit(limit));
    }

    // ---- poolLabel -----------------------------------------------------------------------------

    @Test
    void poolLabel_fiveHour() {
        assertEquals("5-hour", ClaudeQuotaParser.poolLabel("5h"));
    }

    @Test
    void poolLabel_sevenDay() {
        assertEquals("7-day", ClaudeQuotaParser.poolLabel("7d"));
    }

    @Test
    void poolLabel_modelScopedDashSeparator() {
        assertEquals("7-day (Fable)", ClaudeQuotaParser.poolLabel("7d-fable"));
    }

    @Test
    void poolLabel_modelScopedUnderscoreSeparator() {
        assertEquals("7-day (Fable)", ClaudeQuotaParser.poolLabel("7d_fable"));
    }

    @Test
    void poolLabel_unrecognizedShape_passesThroughUnchanged() {
        assertEquals("custom_x", ClaudeQuotaParser.poolLabel("custom_x"));
    }

    @Test
    void poolLabel_bareWordNoDigitPrefix_passesThroughUnchanged() {
        assertEquals("weekly", ClaudeQuotaParser.poolLabel("weekly"));
    }

    // ---- claudeQuota ---------------------------------------------------------------------------

    @Test
    void claudeQuota_discoveredPools_sortedByBucketKey_labeledAndClamped() {
        Map<String, Object> account = map("cachedQuota", map("pools", map(
                "7d", map("utilization", 1.0, "reset", 456000L),
                "5h", map("utilization", 0.2, "reset", 123000L))));
        List<Map<String, Object>> quota = ClaudeQuotaParser.claudeQuota(account);
        assertEquals(2, quota.size());
        assertEquals(map("label", "5-hour", "remainingFraction", 0.8, "resetTime", 123000L), quota.get(0));
        assertEquals(map("label", "7-day", "remainingFraction", 0.0, "resetTime", 456000L), quota.get(1));
    }

    @Test
    void claudeQuota_poolMissingUtilization_skipped() {
        Map<String, Object> account = map("cachedQuota", map("pools", map(
                "5h", map("reset", 123000L),
                "7d", map("utilization", 0.3, "reset", 456000L))));
        List<Map<String, Object>> quota = ClaudeQuotaParser.claudeQuota(account);
        assertEquals(1, quota.size());
        assertEquals(map("label", "7-day", "remainingFraction", 0.7, "resetTime", 456000L), quota.get(0));
    }

    @Test
    void claudeQuota_preDiscoveryFallbackShape_fixedOrderFiveHourThenSevenDay() {
        Map<String, Object> account = map("cachedQuota",
                map("fiveHour", map("utilization", 0.4), "sevenDay", map("utilization", 0.6)));
        List<Map<String, Object>> quota = ClaudeQuotaParser.claudeQuota(account);
        assertEquals(2, quota.size());
        assertEquals("5-hour", quota.get(0).get("label"));
        assertEquals(0.6, (Double) quota.get(0).get("remainingFraction"), 1e-9);
        assertEquals("7-day", quota.get(1).get("label"));
        assertEquals(0.4, (Double) quota.get(1).get("remainingFraction"), 1e-9);
    }

    @Test
    void claudeQuota_noCachedQuota_returnsNull() {
        assertNull(ClaudeQuotaParser.claudeQuota(map()));
    }

    @Test
    void claudeQuota_emptyPools_returnsNullNotEmptyList() {
        assertNull(ClaudeQuotaParser.claudeQuota(map("cachedQuota", map("pools", map()))));
    }

    @Test
    void claudeQuotaJson_jsonCodecEntryPoint_matchesMapBasedCore() {
        String accountJson = "{\"cachedQuota\":{\"pools\":{\"5h\":{\"utilization\":0.2,\"reset\":123000}}}}";
        List<Map<String, Object>> quota = parser.claudeQuotaJson(accountJson);
        assertEquals(1, quota.size());
        assertEquals("5-hour", quota.get(0).get("label"));
    }

    // ---- accountHasQuota -----------------------------------------------------------------------

    @Test
    void accountHasQuota_anyPoolBelowFull_true() {
        Map<String, Object> account = map("cachedQuota", map("pools", map("5h", map("utilization", 0.5))));
        assertTrue(ClaudeQuotaParser.accountHasQuota(account));
    }

    @Test
    void accountHasQuota_allPoolsFull_false() {
        Map<String, Object> account = map("cachedQuota", map("pools", map("5h", map("utilization", 1.0))));
        assertFalse(ClaudeQuotaParser.accountHasQuota(account));
    }

    @Test
    void accountHasQuota_noCachedQuota_false() {
        assertFalse(ClaudeQuotaParser.accountHasQuota(map()));
    }

    @Test
    void accountHasQuota_cachedQuotaWithoutPools_false() {
        assertFalse(ClaudeQuotaParser.accountHasQuota(map("cachedQuota", map())));
    }

    @Test
    void accountHasQuotaJson_jsonCodecEntryPoint_matchesMapBasedCore() {
        assertTrue(parser.accountHasQuotaJson("{\"cachedQuota\":{\"pools\":{\"5h\":{\"utilization\":0.5}}}}"));
    }
}
