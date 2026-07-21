package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic parity tests for {@link ClaudeModelRouting}, checked against
 * claude-code-auth's actual {@code src/driver/index.ts} behavior: {@code isRateLimitStatus},
 * {@code resolveAutoModel}, {@code applyAssignedModel}, and the mapping half of {@code
 * fetchModels}.
 */
class ClaudeModelRoutingTest {

    private final JsonCodec json = new TestJsonCodec();

    // ---- isRateLimitStatus -------------------------------------------------------------------

    @Test
    void isRateLimitStatus_429and529_true_othersFalse() {
        assertTrue(ClaudeModelRouting.isRateLimitStatus(429));
        assertTrue(ClaudeModelRouting.isRateLimitStatus(529));
        for (int s : new int[] { 200, 401, 403, 500, 528, 430 }) {
            assertFalse(ClaudeModelRouting.isRateLimitStatus(s), "status " + s);
        }
    }

    // ---- resolveAutoModel ---------------------------------------------------------------------

    @Test
    void resolveAutoModel_autoId_candidatePresent_rewritesModel() {
        String out = ClaudeModelRouting.resolveAutoModel(json, "{\"model\":\"claude-code-auto\",\"messages\":[]}",
                null, "claude-opus-4-1");
        assertEquals("{\"model\":\"claude-opus-4-1\",\"messages\":[]}", out);
    }

    @Test
    void resolveAutoModel_autoId_noCandidate_leftAsIs() {
        String body = "{\"model\":\"claude-code-auto\"}";
        String out = ClaudeModelRouting.resolveAutoModel(json, body, null, null);
        assertEquals(body, out);
    }

    @Test
    void resolveAutoModel_nonAutoId_untouched() {
        String body = "{\"model\":\"claude-sonnet-4-5\"}";
        String out = ClaudeModelRouting.resolveAutoModel(json, body, null, "claude-opus-4-1");
        assertEquals(body, out);
    }

    @Test
    void resolveAutoModel_malformedJson_passthroughVerbatim() {
        String body = "not json{{{";
        String out = ClaudeModelRouting.resolveAutoModel(json, body, null, "claude-opus-4-1");
        assertEquals(body, out);
    }

    @Test
    void resolveAutoModel_noBodyModel_ctxModelAuto_rewritesFromCtx() {
        String out = ClaudeModelRouting.resolveAutoModel(json, "{\"messages\":[]}", "claude-code-auto", "claude-haiku-4-5");
        assertEquals("{\"messages\":[],\"model\":\"claude-haiku-4-5\"}", out);
    }

    @Test
    void resolveAutoModel_emptyBody_passthrough() {
        assertEquals("", ClaudeModelRouting.resolveAutoModel(json, "", null, "claude-opus-4-1"));
    }

    @Test
    void resolveAutoModel_nullBody_passthrough() {
        assertNull(ClaudeModelRouting.resolveAutoModel(json, null, null, "claude-opus-4-1"));
    }

    // ---- applyAssignedModel -------------------------------------------------------------------

    @Test
    void applyAssignedModel_differingModel_rewritesAndLogs() {
        List<String> logs = new ArrayList<>();
        String out = ClaudeModelRouting.applyAssignedModel(json, "{\"model\":\"claude-sonnet-4-5\",\"messages\":[]}",
                "claude-opus-4-1", logs::add);
        assertEquals("{\"model\":\"claude-opus-4-1\",\"messages\":[]}", out);
        assertEquals(List.of("model rewrite: claude-sonnet-4-5 -> claude-opus-4-1 (tier assignment)"), logs);
    }

    @Test
    void applyAssignedModel_equalModel_noOp() {
        String body = "{\"model\":\"claude-opus-4-1\"}";
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, "claude-opus-4-1", m -> fail("must not log")));
    }

    @Test
    void applyAssignedModel_ctxModelEmpty_noOp() {
        String body = "{\"model\":\"claude-sonnet-4-5\"}";
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, "", m -> fail("must not log")));
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, null, m -> fail("must not log")));
    }

    @Test
    void applyAssignedModel_ctxModelIsAutoId_noOp() {
        String body = "{\"model\":\"claude-sonnet-4-5\"}";
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, "claude-code-auto", m -> fail("must not log")));
    }

    @Test
    void applyAssignedModel_bodyModelMissing_noOp() {
        String body = "{\"messages\":[]}";
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, "claude-opus-4-1", m -> fail("must not log")));
    }

    @Test
    void applyAssignedModel_malformedJson_passthroughVerbatim() {
        String body = "not json{{{";
        assertEquals(body, ClaudeModelRouting.applyAssignedModel(json, body, "claude-opus-4-1", m -> fail("must not log")));
    }

    @Test
    void applyAssignedModel_emptyBody_passthrough() {
        assertEquals("", ClaudeModelRouting.applyAssignedModel(json, "", "claude-opus-4-1", m -> fail("must not log")));
    }

    // ---- fetchModelsMapping -------------------------------------------------------------------

    @Test
    void fetchModelsMapping_filtersNonClaude_namesWithDisplayNameOrIdSuffix() {
        String modelsJson = "{\"data\":[" +
                "{\"id\":\"claude-opus-4-1\",\"display_name\":\"Claude Opus 4.1\"}," +
                "{\"id\":\"gpt-4\",\"display_name\":\"GPT-4\"}," +
                "{\"id\":\"claude-haiku-4-5\"}," +
                "{\"id\":\"\"}," +
                "null" +
                "]}";
        String out = ClaudeModelRouting.fetchModelsMapping(json, modelsJson);
        assertEquals("{\"models\":{\"claude-opus-4-1\":{\"name\":\"Claude Opus 4.1 (Claude Code)\"}," +
                "\"claude-haiku-4-5\":{\"name\":\"claude-haiku-4-5 (Claude Code)\"}}}", out);
    }

    @Test
    void fetchModelsMapping_emptyData_null() {
        assertNull(ClaudeModelRouting.fetchModelsMapping(json, "{\"data\":[]}"));
    }

    @Test
    void fetchModelsMapping_noDataField_null() {
        assertNull(ClaudeModelRouting.fetchModelsMapping(json, "{}"));
    }

    @Test
    void fetchModelsMapping_nullInput_null() {
        assertNull(ClaudeModelRouting.fetchModelsMapping(json, null));
    }
}
