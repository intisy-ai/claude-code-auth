package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed-capability parity test for {@link ClaudeConfig}: {@link ClaudeConfig#schema()}/{@link
 * ClaudeConfig#values}/{@link ClaudeConfig#putValues}, same 5 settings, same persistence
 * semantics.
 */
class ClaudeConfigTest {

    @Test
    void schemaExposesFiveRealSettings() {
        ConfigSchema schema = ClaudeConfig.schema();
        assertEquals(1, schema.groups.size());
        ConfigGroup group = schema.groups.get(0);
        assertEquals("Claude", group.title);

        List<String> keys = group.fields.stream().map(f -> f.key).collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("logging", "max_account_attempts", "account_selection_strategy",
                "default_cooldown_seconds", "max_cooldown_seconds"), keys);

        ConfigField logging = group.fields.stream()
                .filter(f -> f.key.equals("logging")).findFirst().orElseThrow(NoSuchElementException::new);
        assertEquals("bool", logging.type, "type must use the dashboard vocabulary (bool), not \"boolean\"");

        ConfigField attempts = group.fields.stream()
                .filter(f -> f.key.equals("max_account_attempts")).findFirst().orElseThrow(NoSuchElementException::new);
        assertEquals("number", attempts.type);

        ConfigField strategy = group.fields.stream()
                .filter(f -> f.key.equals("account_selection_strategy")).findFirst().orElseThrow(NoSuchElementException::new);
        assertEquals("select", strategy.type, "type must use the dashboard vocabulary (select), not \"enum\"");
        assertTrue(strategy.options.contains("sticky"));
        assertTrue(strategy.options.contains("round-robin"));
        assertTrue(strategy.options.contains("hybrid"));
        assertEquals("hybrid", strategy.defaultValue);
    }

    @Test
    void valuesReturnsDefaultsWhenNothingPersisted(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forConfigDir(dir.toString());
        Map<String, Object> values = ClaudeConfig.values(backend);
        assertEquals(Boolean.TRUE, values.get("logging"));
        assertEquals(4L, values.get("max_account_attempts"));
        assertEquals("hybrid", values.get("account_selection_strategy"));
    }

    @Test
    void putValuesPersistsAndReReadsMergedValues(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forConfigDir(dir.toString());
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("max_account_attempts", 7L);
        overrides.put("account_selection_strategy", "sticky");
        Map<String, Object> updated = ClaudeConfig.putValues(backend, overrides);
        assertEquals(7L, updated.get("max_account_attempts"));
        assertEquals("sticky", updated.get("account_selection_strategy"));

        ClaudeBackend backend2 = ClaudeBackend.forConfigDir(dir.toString());
        Map<String, Object> reread = ClaudeConfig.values(backend2);
        assertEquals(7L, reread.get("max_account_attempts"));
        assertEquals("sticky", reread.get("account_selection_strategy"));
        // Untouched fields keep their default.
        assertEquals(Boolean.TRUE, reread.get("logging"));
    }

    @Test
    void putValuesIgnoresInvalidEnumValue_keepsPriorOverride(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forConfigDir(dir.toString());
        ClaudeConfig.putValues(backend, Collections.singletonMap("account_selection_strategy", "sticky"));
        Map<String, Object> updated = ClaudeConfig.putValues(backend,
                Collections.singletonMap("account_selection_strategy", "not-a-real-strategy"));
        assertEquals("sticky", updated.get("account_selection_strategy"), "invalid value must be ignored, not persisted");
    }
}
