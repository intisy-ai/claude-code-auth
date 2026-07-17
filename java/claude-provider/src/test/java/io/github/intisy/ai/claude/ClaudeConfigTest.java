package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeConfigTest {
    @Test
    void configExposesFiveRealSettings(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forConfigDir(dir.toString());
        HttpResponse r = ClaudeConfig.config(backend);
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"logging\""), r.body);
        assertTrue(r.body.contains("\"max_account_attempts\""), r.body);
        assertTrue(r.body.contains("\"account_selection_strategy\""), r.body);
        assertTrue(r.body.contains("\"default_cooldown_seconds\""), r.body);
        assertTrue(r.body.contains("\"max_cooldown_seconds\""), r.body);
        assertTrue(r.body.contains("sticky"), r.body);
        assertTrue(r.body.contains("round-robin"), r.body);
        assertTrue(r.body.contains("hybrid"), r.body);
    }

    @Test
    void putConfigPersistsValues(@TempDir Path dir) {
        ClaudeBackend backend = ClaudeBackend.forConfigDir(dir.toString());
        HttpResponse put = ClaudeConfig.putConfig(backend,
            "{\"values\":{\"max_account_attempts\":7,\"account_selection_strategy\":\"sticky\"}}");
        assertEquals(200, put.status, put.body);
        ClaudeBackend backend2 = ClaudeBackend.forConfigDir(dir.toString());
        HttpResponse r = ClaudeConfig.config(backend2);
        assertTrue(r.body.replace(" ", "").contains("\"max_account_attempts\":7"), r.body);
        assertTrue(r.body.contains("sticky"), r.body);
    }
}
