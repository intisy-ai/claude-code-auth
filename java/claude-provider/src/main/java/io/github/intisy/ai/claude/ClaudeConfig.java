package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM-only config side-path for the Claude provider (mirrors {@link ClaudeOAuth}'s shape):
 * exposes the 5 real settings registered by {@code defineConfig("claude-code", …)} in
 * {@code src/index.ts:13-19}, with working GET (merged defaults + persisted overrides) and
 * PUT (validate/coerce + persist) against the backend {@link io.github.intisy.ai.shared.spi.Store}
 * under the SAME key name the TS driver's settings.ts reads ({@code config/claude-code.json}),
 * so JVM and TS share one on-disk config file.
 */
final class ClaudeConfig {

    // Matches the TS config-file name (config/claude-code.json) exactly -- see settings.ts.
    // NOTE: the backend's Store is already rooted at the "config" folder (mirrors how
    // AccountStore's own KEY is the flat "accounts.json", not "config/accounts.json" -- see
    // ClaudeBackend/FileStore), so the key here is just the filename, not the "config/" prefix.
    private static final String STORE_KEY = "claude-code.json";

    private static final List<Field> FIELDS = Arrays.asList(
            new Field("logging", "Logging", "boolean", null, Boolean.TRUE),
            new Field("max_account_attempts", "Max account attempts", "number", null, 4L),
            new Field("account_selection_strategy", "Account selection strategy", "enum",
                    Arrays.asList("sticky", "round-robin", "hybrid"), "hybrid"),
            new Field("default_cooldown_seconds", "Default cooldown seconds", "number", null, 60L),
            new Field("max_cooldown_seconds", "Max cooldown seconds", "number", null, 900L)
    );

    private ClaudeConfig() {
    }

    static HttpResponse config(ClaudeBackend backend) {
        Map<String, Object> persisted = readPersisted(backend);

        List<Object> fields = new ArrayList<>();
        for (Field f : FIELDS) {
            Map<String, Object> fieldJson = new LinkedHashMap<>();
            fieldJson.put("key", f.key);
            fieldJson.put("label", f.label);
            fieldJson.put("type", f.type);
            if (f.options != null) fieldJson.put("options", f.options);
            fieldJson.put("default", f.defaultValue);
            fields.add(fieldJson);
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("title", "Claude");
        group.put("fields", fields);

        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> groups = new ArrayList<>();
        groups.add(group);
        root.put("groups", groups);
        root.put("values", mergedValues(persisted));
        return json(backend, 200, root);
    }

    static HttpResponse putConfig(ClaudeBackend backend, String requestBody) {
        Object parsed;
        try {
            parsed = backend.json.parse(requestBody != null ? requestBody : "");
        } catch (Exception e) {
            return json(backend, 400, errorRoot("malformed request body"));
        }
        Map<String, Object> body = asMap(parsed);
        Map<String, Object> incoming = body != null ? asMap(body.get("values")) : null;
        if (body == null || incoming == null) {
            return json(backend, 400, errorRoot("missing values object"));
        }

        // Only known keys are ever written; the store keeps overrides only (not baked-in
        // defaults), so a field never explicitly set stays absent and future default changes
        // still take effect for it.
        Map<String, Object> persisted = readPersisted(backend);
        Map<String, Object> overrides = persisted != null ? new LinkedHashMap<>(persisted) : new LinkedHashMap<>();
        for (Field f : FIELDS) {
            if (!incoming.containsKey(f.key)) continue;
            Object coerced = coerce(f, incoming.get(f.key));
            // An invalid/unknown value (e.g. an enum outside its options) is ignored rather
            // than rejecting the whole request, leaving any prior override/default in place.
            if (coerced != null) overrides.put(f.key, coerced);
        }

        backend.store.put(STORE_KEY, backend.json.stringify(overrides));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("values", mergedValues(overrides));
        return json(backend, 200, root);
    }

    // --- helpers ---

    private static Map<String, Object> readPersisted(ClaudeBackend backend) {
        String raw = backend.store.get(STORE_KEY);
        if (raw == null || raw.isEmpty()) return null;
        return asMap(backend.json.parse(raw));
    }

    private static Map<String, Object> mergedValues(Map<String, Object> persisted) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            Object v = persisted != null ? persisted.get(f.key) : null;
            values.put(f.key, v != null ? v : f.defaultValue);
        }
        return values;
    }

    private static Object coerce(Field f, Object raw) {
        switch (f.type) {
            case "boolean":
                return raw instanceof Boolean ? raw : null;
            case "number":
                return raw instanceof Number ? raw : null;
            case "enum":
                return (raw instanceof String && f.options.contains(raw)) ? raw : null;
            default:
                return null;
        }
    }

    private static Map<String, Object> errorRoot(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static HttpResponse json(ClaudeBackend backend, int status, Object body) {
        HttpResponse r = new HttpResponse();
        r.status = status;
        r.headers = new LinkedHashMap<>();
        r.headers.put("content-type", "application/json");
        r.body = backend.json.stringify(body);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static final class Field {
        final String key;
        final String label;
        final String type;
        final List<String> options;
        final Object defaultValue;

        Field(String key, String label, String type, List<String> options, Object defaultValue) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.options = options;
            this.defaultValue = defaultValue;
        }
    }
}
