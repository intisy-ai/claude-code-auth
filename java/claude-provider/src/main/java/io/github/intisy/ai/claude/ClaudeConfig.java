package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 *
 * <p>Exposes the typed {@link io.github.intisy.ai.shared.routing.ConfigurableProvider} shape
 * ({@link #schema()}/{@link #values}/{@link #putValues}) that {@link ClaudeProvider} implements
 * directly -- there is no HttpResponse/JSON wrapper here since nothing else (TeaVM export surface,
 * TS driver) ever called the old {@code GET/PUT /v1/config} branch; those lived ONLY in {@code
 * ClaudeProvider#handle}'s retired URL branches. {@code type} strings use the SPI/dashboard
 * vocabulary ({@code bool}/{@code select}/{@code number}/{@code text}) exactly -- the example-server
 * grouped-config renderer branches STRICTLY on those tokens (anything else falls through to a plain
 * text input), so the pre-migration internal tokens {@code "boolean"}/{@code "enum"} would have made
 * Logging render as a text box and Account-selection as a text box instead of a checkbox/dropdown.
 */
final class ClaudeConfig {

    // Matches the TS config-file name (config/claude-code.json) exactly -- see settings.ts.
    // NOTE: the backend's Store is already rooted at the "config" folder (mirrors how
    // AccountStore's own KEY is the flat "accounts.json", not "config/accounts.json" -- see
    // ClaudeBackend/FileStore), so the key here is just the filename, not the "config/" prefix.
    private static final String STORE_KEY = "claude-code.json";

    // type tokens use the SPI/dashboard vocabulary (bool/select/number/text) directly -- the
    // grouped-config renderer branches STRICTLY on these, so "boolean"/"enum" would render as
    // plain text inputs instead of a checkbox/dropdown.
    private static final List<Field> FIELDS = Arrays.asList(
            new Field("logging", "Logging", "bool", null, Boolean.TRUE),
            new Field("max_account_attempts", "Max account attempts", "number", null, 4L),
            new Field("account_selection_strategy", "Account selection strategy", "select",
                    Arrays.asList("sticky", "round-robin", "hybrid"), "hybrid"),
            new Field("default_cooldown_seconds", "Default cooldown seconds", "number", null, 60L),
            new Field("max_cooldown_seconds", "Max cooldown seconds", "number", null, 900L)
    );

    private ClaudeConfig() {
    }

    /** {@link io.github.intisy.ai.shared.routing.ConfigurableProvider#configSchema}. */
    static ConfigSchema schema() {
        List<ConfigField> fields = new ArrayList<>();
        for (Field f : FIELDS) {
            fields.add(new ConfigField(f.key, f.label, f.type, f.options, f.defaultValue));
        }
        return new ConfigSchema(Collections.singletonList(new ConfigGroup("Claude", fields)));
    }

    /** {@link io.github.intisy.ai.shared.routing.ConfigurableProvider#getConfigValues}. */
    static Map<String, Object> values(ClaudeBackend backend) {
        return mergedValues(readPersisted(backend));
    }

    /**
     * {@link io.github.intisy.ai.shared.routing.ConfigurableProvider#putConfigValues}: {@code
     * incoming} is already a parsed values map (the caller owns request-body JSON parsing now --
     * that concern moved out of this provider with the retired {@code PUT /v1/config} branch).
     * Only known keys are ever written; the store keeps overrides only (not baked-in defaults), so
     * a field never explicitly set stays absent and future default changes still take effect for
     * it. An invalid/unknown value (e.g. an enum outside its options) is ignored rather than
     * rejecting the whole call, leaving any prior override/default in place.
     */
    static Map<String, Object> putValues(ClaudeBackend backend, Map<String, Object> incoming) {
        Map<String, Object> persisted = readPersisted(backend);
        Map<String, Object> overrides = persisted != null ? new LinkedHashMap<>(persisted) : new LinkedHashMap<>();
        if (incoming != null) {
            for (Field f : FIELDS) {
                if (!incoming.containsKey(f.key)) continue;
                Object coerced = coerce(f, incoming.get(f.key));
                if (coerced != null) overrides.put(f.key, coerced);
            }
        }
        backend.store.put(STORE_KEY, backend.json.stringify(overrides));
        return mergedValues(overrides);
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
            case "bool":
                return raw instanceof Boolean ? raw : null;
            case "number":
                return raw instanceof Number ? raw : null;
            case "select":
                return (raw instanceof String && f.options.contains(raw)) ? raw : null;
            case "text":
                return raw instanceof String ? raw : null;
            default:
                return null;
        }
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
