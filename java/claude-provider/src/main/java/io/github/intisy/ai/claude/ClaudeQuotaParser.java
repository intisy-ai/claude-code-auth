package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of claude-code-auth's {@code src/driver/accounts-controller.ts} (Bucket A of
 * {@code .superpowers/port-grounding-map.md}): {@code readPools}, {@code bucketOfLimit},
 * {@code poolLabel}, {@code claudeQuota}, and {@code accountHasQuota}. The I/O-bound siblings
 * ({@code captureQuota}, {@code fetchUsagePools}, {@code refreshQuota*}, {@code verify*}) stay in
 * TS (Bucket B, HttpClient/Store) -- out of scope here.
 *
 * <p>The core logic works over plain {@code Map}/{@code List} JSON trees (the shape both gson and
 * {@link JsonCodec} produce) so it is TeaVM-transpilable on its own; the instance methods taking
 * a JSON string additionally use the injected {@link JsonCodec} SPI to parse it, matching how the
 * real host stores accounts/limits as JSON.
 */
public final class ClaudeQuotaParser {

    // accounts-controller.ts:20 -- discovers "anthropic-ratelimit-unified-{pool}-{field}" header
    // triples; the bucketless "anthropic-ratelimit-unified-reset" lane-timing header does NOT
    // match (no hyphen-separated pool component for (.+) to capture before the trailing field).
    private static final Pattern UNIFIED_POOL_HEADER =
            Pattern.compile("^anthropic-ratelimit-unified-(.+)-(utilization|reset|status)$");

    // accounts-controller.ts:94 -- "5h" -> ("5","h",null); "7d-fable"/"7d_fable" -> ("7","d","fable").
    private static final Pattern BUCKET_LABEL = Pattern.compile("^(\\d+)([hd])(?:[-_](.+))?$");

    private final JsonCodec json;

    public ClaudeQuotaParser(JsonCodec json) {
        this.json = json;
    }

    // ---- readPools (accounts-controller.ts:22-36) ---------------------------------------------

    /**
     * Discovers unified rate-limit pools from response headers. Header names are matched
     * case-insensitively (lowercased first, matching the TS's own {@code .toLowerCase()}).
     * A pool that ends up with neither a numeric {@code utilization} NOR a numeric {@code reset}
     * (e.g. only a garbage/non-numeric field, or only a {@code status}) is dropped entirely --
     * matches the TS's post-loop cleanup pass exactly.
     *
     * @return a map of pool-key -&gt; {@code {utilization: Double, reset: Long (epoch ms),
     *         status: String}} (only the keys that were actually captured are present).
     */
    public static Map<String, Map<String, Object>> readPools(Map<String, String> headers) {
        Map<String, Map<String, Object>> pools = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().toLowerCase();
                Matcher m = UNIFIED_POOL_HEADER.matcher(name);
                if (!m.matches()) continue;
                String poolKey = m.group(1);
                String field = m.group(2);
                Map<String, Object> pool = pools.get(poolKey);
                if (pool == null) {
                    pool = new LinkedHashMap<>();
                    pools.put(poolKey, pool);
                }
                String value = e.getValue();
                if ("utilization".equals(field)) {
                    double v = JsCoercion.jsParseFloat(value);
                    if (!Double.isNaN(v)) pool.put("utilization", v);
                } else if ("reset".equals(field)) {
                    double v = JsCoercion.jsParseInt10(value);
                    if (!Double.isNaN(v)) pool.put("reset", (long) (v * 1000));
                } else if (!JsCoercion.isFalsyString(value)) {
                    pool.put("status", value);
                }
            }
        }
        Iterator<Map.Entry<String, Map<String, Object>>> it = pools.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, Object> pool = it.next().getValue();
            boolean hasUtilization = pool.get("utilization") instanceof Number;
            boolean hasReset = pool.get("reset") instanceof Number;
            if (!hasUtilization && !hasReset) it.remove();
        }
        return pools;
    }

    // ---- bucketOfLimit (accounts-controller.ts:56-65) ------------------------------------------

    /**
     * Canonical bucket key for one entry of the {@code /api/oauth/usage} endpoint's
     * {@code limits[]} array: {@code kind === "session"} -&gt; {@code "5h"}; {@code kind ===
     * "weekly_all"} -&gt; {@code "7d"}; {@code group === "weekly"} with a model scope -&gt;
     * {@code "7d-<scope>"}; otherwise {@code kind || group}, optionally suffixed with the scope.
     * Returns {@code null} for a non-map {@code limit} or when neither {@code kind} nor
     * {@code group} is present.
     */
    @SuppressWarnings("unchecked")
    public static String bucketOfLimit(Map<String, Object> limit) {
        if (limit == null) return null;
        String kind = asTruthyString(limit.get("kind"));
        String group = asTruthyString(limit.get("group"));
        String scopeKey = scopeKeyOf(limit);

        if ("session".equals(kind)) return "5h";
        if ("weekly_all".equals(kind)) return "7d";
        if ("weekly".equals(group) && !scopeKey.isEmpty()) return "7d-" + scopeKey;

        String base = kind != null ? kind : (group != null ? group : "");
        if (base.isEmpty()) return null;
        return scopeKey.isEmpty() ? base : base + "-" + scopeKey;
    }

    // Mirrors JS `x || fallback` truthy-string extraction: null for anything falsy (null,
    // missing, "", or a non-string that stringifies oddly is not expected here per the API
    // shape) so callers can chain `kind != null ? kind : group`.
    private static String asTruthyString(Object v) {
        if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String scopeKeyOf(Map<String, Object> limit) {
        Object scopeObj = limit.get("scope");
        if (!JsCoercion.isTruthy(scopeObj) || !(scopeObj instanceof Map)) return "";
        Object modelObj = ((Map<String, Object>) scopeObj).get("model");
        if (!JsCoercion.isTruthy(modelObj) || !(modelObj instanceof Map)) return "";
        Map<String, Object> model = (Map<String, Object>) modelObj;
        Object display = model.get("display_name");
        Object scope = JsCoercion.isTruthy(display) ? display : model.get("id");
        if (!JsCoercion.isTruthy(scope)) return "";
        return String.valueOf(scope).toLowerCase().replaceAll("\\s+", "-");
    }

    // ---- poolLabel (accounts-controller.ts:93-98) ---------------------------------------------

    /**
     * Bucket key -&gt; human label: {@code "5h"} -&gt; {@code "5-hour"}, {@code "7d"} -&gt;
     * {@code "7-day"}; a model-scoped bucket like {@code "7d-fable"} or {@code "7d_fable"} -&gt;
     * {@code "7-day (Fable)"}; anything not matching the {@code <digits><h|d>[-_<scope>]} shape
     * passes through unchanged (including {@code null}).
     */
    public static String poolLabel(String bucket) {
        if (bucket == null) return null;
        Matcher m = BUCKET_LABEL.matcher(bucket);
        if (!m.matches()) return bucket;
        String amount = m.group(1);
        String unit = m.group(2);
        String scope = m.group(3);
        String base = amount + ("h".equals(unit) ? "-hour" : "-day");
        return scope == null ? base : base + " (" + capitalize(scope) + ")";
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- claudeQuota (accounts-controller.ts:101-112) ------------------------------------------

    /**
     * Maps a stored account's cached pools to core-auth's quota shape:
     * {@code [{label, remainingFraction, resetTime}]}, sorted by bucket key (natural/ASCII
     * ordering, used here in place of {@code String.localeCompare} -- equivalent for the ASCII
     * bucket keys this provider ever produces). A pool missing a numeric {@code utilization} is
     * skipped. Falls back to the pre-discovery cached shape ({@code fiveHour}/{@code sevenDay})
     * when {@code cachedQuota.pools} is absent. Returns {@code null} (TS: {@code undefined}) when
     * there is no cached quota at all, or the result would be empty.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> claudeQuota(Map<String, Object> account) {
        if (account == null) return null;
        Object q = account.get("cachedQuota");
        if (!(q instanceof Map)) return null;
        Map<String, Object> quota = (Map<String, Object>) q;
        List<Map<String, Object>> result = new ArrayList<>();

        Object poolsObj = quota.get("pools");
        if (poolsObj instanceof Map) {
            Map<String, Object> pools = (Map<String, Object>) poolsObj;
            List<String> keys = new ArrayList<>(pools.keySet());
            Collections.sort(keys);
            for (String bucket : keys) {
                addPool(result, asMap(pools.get(bucket)), poolLabel(bucket));
            }
        } else {
            addPool(result, asMap(quota.get("fiveHour")), "5-hour");
            addPool(result, asMap(quota.get("sevenDay")), "7-day");
        }
        return result.isEmpty() ? null : result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static void addPool(List<Map<String, Object>> out, Map<String, Object> pool, String label) {
        if (pool == null) return;
        Object utilObj = pool.get("utilization");
        if (!(utilObj instanceof Number)) return;
        double utilization = ((Number) utilObj).doubleValue();
        double remaining = Math.max(0.0, Math.min(1.0, 1.0 - utilization));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("remainingFraction", remaining);
        entry.put("resetTime", pool.get("reset"));
        out.add(entry);
    }

    // ---- accountHasQuota (accounts-controller.ts:208-213) --------------------------------------

    /** True if any cached pool has a numeric utilization below 1. Unknown/missing -&gt; false. */
    @SuppressWarnings("unchecked")
    public static boolean accountHasQuota(Map<String, Object> account) {
        if (account == null) return false;
        Object q = account.get("cachedQuota");
        if (!(q instanceof Map)) return false;
        Object poolsObj = ((Map<String, Object>) q).get("pools");
        if (!(poolsObj instanceof Map)) return false;
        for (Object poolObj : ((Map<String, Object>) poolsObj).values()) {
            if (poolObj instanceof Map) {
                Object utilObj = ((Map<?, ?>) poolObj).get("utilization");
                if (utilObj instanceof Number && ((Number) utilObj).doubleValue() < 1.0) return true;
            }
        }
        return false;
    }

    // ---- JsonCodec-backed convenience entry points (the SPI use this class needs) -------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseLimit(String limitJson) {
        Object parsed = json.parse(limitJson);
        return (parsed instanceof Map) ? (Map<String, Object>) parsed : null;
    }

    public String bucketOfLimitJson(String limitJson) {
        return bucketOfLimit(parseLimit(limitJson));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAccount(String accountJson) {
        Object parsed = json.parse(accountJson);
        return (parsed instanceof Map) ? (Map<String, Object>) parsed : null;
    }

    public List<Map<String, Object>> claudeQuotaJson(String accountJson) {
        return claudeQuota(parseAccount(accountJson));
    }

    public boolean accountHasQuotaJson(String accountJson) {
        return accountHasQuota(parseAccount(accountJson));
    }
}
