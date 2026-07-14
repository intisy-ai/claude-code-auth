package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.claude.Fixtures.list;
import static io.github.intisy.ai.claude.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic parity tests for {@link AnthropicRequestTranslator}, checked against
 * claude-code-auth's actual {@code src/plugin/request.ts} behavior: the TS's pure functions were
 * extracted verbatim (backoff-fallback branch omitted) into a throwaway Node harness and executed
 * with {@code node} (v26.3.1) to snapshot the exact expected values used below -- not
 * hand-derived from reading the source alone. See task-6a-report.md for the harness.
 */
class AnthropicRequestTranslatorTest {

    private static final String CCS = AnthropicRequestTranslator.CLAUDE_CODE_SYSTEM;
    private final JsonCodec json = new TestJsonCodec();

    // ---- ensureClaudeCodeSystem ----------------------------------------------------------

    @Test
    void ensureClaudeCodeSystem_absentSystem_injectsIdentityBlock() {
        Map<String, Object> body = map("model", "x");
        Object result = AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertSame(body, result);
        assertEquals(list(map("type", "text", "text", CCS)), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_stringExactlyIdentity_dedupesToSingleBlock() {
        Map<String, Object> body = map("system", CCS);
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(list(map("type", "text", "text", CCS)), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_stringOther_prependsIdentityKeepingOriginalAsSecondBlock() {
        Map<String, Object> body = map("system", "custom system prompt");
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(
                list(map("type", "text", "text", CCS), map("type", "text", "text", "custom system prompt")),
                body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_arrayAlreadyStartingWithIdentity_leftUntouched() {
        List<Object> system = list(map("type", "text", "text", CCS), map("type", "text", "text", "extra"));
        Map<String, Object> body = map("system", system);
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(list(map("type", "text", "text", CCS), map("type", "text", "text", "extra")), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_arrayNotStartingWithIdentity_identityPrepended() {
        Map<String, Object> body = map("system", list(map("type", "text", "text", "custom")));
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(list(map("type", "text", "text", CCS), map("type", "text", "text", "custom")), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_explicitNullSystem_treatedAsElseBranch_becomesIdentityOnly() {
        // Edge case: typeof null !== "string" and Array.isArray(null) is false in JS, so an
        // explicit `system: null` falls into the TS `else` branch, same as an absent key.
        Map<String, Object> body = map("system", null);
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(list(map("type", "text", "text", CCS)), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_nonStringNonArraySystem_replacedWithIdentityOnly() {
        Map<String, Object> body = map("system", 42L);
        AnthropicRequestTranslator.ensureClaudeCodeSystem(body);
        assertEquals(list(map("type", "text", "text", CCS)), body.get("system"));
    }

    @Test
    void ensureClaudeCodeSystem_bodyNotAnObject_returnedUnchanged() {
        assertEquals("not an object", AnthropicRequestTranslator.ensureClaudeCodeSystem("not an object"));
    }

    @Test
    void ensureClaudeCodeSystem_nullBody_returnedUnchanged() {
        assertNull(AnthropicRequestTranslator.ensureClaudeCodeSystem(null));
    }

    // ---- mergeBeta -------------------------------------------------------------------------

    @Test
    void mergeBeta_nullExisting_returnsBetaAlone() {
        assertEquals("oauth-2025-04-20", AnthropicRequestTranslator.mergeBeta(null));
    }

    @Test
    void mergeBeta_emptyExisting_returnsBetaAlone() {
        assertEquals("oauth-2025-04-20", AnthropicRequestTranslator.mergeBeta(""));
    }

    @Test
    void mergeBeta_newValue_appendsCommaBeta() {
        assertEquals("some-other-beta,oauth-2025-04-20", AnthropicRequestTranslator.mergeBeta("some-other-beta"));
    }

    @Test
    void mergeBeta_exactlyBetaAlready_leftUnchanged() {
        assertEquals("oauth-2025-04-20", AnthropicRequestTranslator.mergeBeta("oauth-2025-04-20"));
    }

    @Test
    void mergeBeta_substringMatch_leftUnchanged_matchesTsIncludesLiterally() {
        // TS uses `existing.includes(BETA)` -- a substring check, not comma-list membership.
        // "foo-oauth-2025-04-20-bar" is (arguably incorrectly) treated as already containing the
        // flag. Matched literally per the brief, not "improved".
        assertEquals("foo-oauth-2025-04-20-bar", AnthropicRequestTranslator.mergeBeta("foo-oauth-2025-04-20-bar"));
    }

    @Test
    void mergeBeta_alreadyInCommaList_leftUnchanged() {
        assertEquals("a,oauth-2025-04-20,b", AnthropicRequestTranslator.mergeBeta("a,oauth-2025-04-20,b"));
    }

    // ---- prepareClaudeRequest ---------------------------------------------------------------

    private static AnthropicRequestTranslator.RequestInit init(String method, Map<String, String> headers, String body) {
        AnthropicRequestTranslator.RequestInit init = new AnthropicRequestTranslator.RequestInit();
        init.method = method;
        init.headers = headers;
        init.body = body;
        return init;
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void prepareClaudeRequest_postJson_rewritesHeadersAndInjectsSystemBlock() {
        String body = json.stringify(map("model", "claude-x", "stream", true, "system", "hi"));
        AnthropicRequestTranslator.RequestInit init = init("POST",
                headers("X-Api-Key", "sk-1", "Host", "example.com", "Content-Length", "10",
                        "Accept-Encoding", "gzip", "Anthropic-Beta", "custom-beta"),
                body);

        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-1");

        assertEquals("https://api.anthropic.com/v1/messages", result.request);
        assertEquals("POST", result.method);
        assertTrue(result.streaming);
        assertEquals("Bearer acc-token-1", result.headers.get("authorization"));
        assertEquals("2023-06-01", result.headers.get("anthropic-version"));
        assertEquals("custom-beta,oauth-2025-04-20", result.headers.get("anthropic-beta"));
        assertEquals("application/json", result.headers.get("content-type"));
        assertFalse(result.headers.containsKey("x-api-key"));
        assertFalse(result.headers.containsKey("host"));
        assertFalse(result.headers.containsKey("content-length"));
        assertFalse(result.headers.containsKey("accept-encoding"));

        Object parsedBody = json.parse(result.body);
        assertEquals(
                map("model", "claude-x", "stream", true, "system",
                        list(map("type", "text", "text", CCS), map("type", "text", "text", "hi"))),
                parsedBody);
    }

    @Test
    void prepareClaudeRequest_getStripsBody() {
        AnthropicRequestTranslator.RequestInit init = init("GET", headers(), json.stringify(map("ignored", true)));
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/models", init, "acc-token-2");
        assertNull(result.body);
        assertEquals("GET", result.method);
        assertFalse(result.streaming);
    }

    @Test
    void prepareClaudeRequest_headStripsBody_evenWithNonJsonBody() {
        AnthropicRequestTranslator.RequestInit init = init("HEAD", headers(), "not even json");
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/models", init, "acc-token-3");
        assertNull(result.body);
        assertEquals("HEAD", result.method);
    }

    @Test
    void prepareClaudeRequest_absoluteUrlWithQuery_pathAndQueryStripped() {
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), null);
        AnthropicRequestTranslator.PreparedRequest result = AnthropicRequestTranslator.prepareClaudeRequest(
                json, "https://api.anthropic.com/v1/messages?x=1", init, "acc-token-4");
        assertEquals("https://api.anthropic.com/v1/messages", result.request);
        assertNull(result.body);
        assertFalse(result.streaming);
    }

    @Test
    void prepareClaudeRequest_defaultMethodAndExistingVersionPreserved() {
        AnthropicRequestTranslator.RequestInit init = init(null, headers("anthropic-version", "2020-01-01"), null);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-5");
        assertEquals("POST", result.method); // init.method falsy -> defaults to POST
        assertEquals("2020-01-01", result.headers.get("anthropic-version")); // not overwritten
        assertNull(result.body);
    }

    @Test
    void prepareClaudeRequest_malformedJsonBody_leftAsOriginalBytesNoSystemInjection() {
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), "{not json");
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-6");
        assertEquals("{not json", result.body); // parse failed -> bodyText passed through verbatim
        assertFalse(result.streaming);
    }

    // ---- parseResetMs (header-parse half only) ----------------------------------------------

    @Test
    void parseResetMs_unifiedResetHeader_usedDirectly() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-reset", "1700000000");
        assertEquals(1700000000000L, AnthropicRequestTranslator.parseResetMs(h, 0L));
    }

    @Test
    void parseResetMs_unifiedResetZero_fallsThroughToRetryAfter() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-reset", "0", "retry-after", "30");
        long now = 5_000_000L;
        assertEquals(now + 30_000L, AnthropicRequestTranslator.parseResetMs(h, now));
    }

    @Test
    void parseResetMs_unifiedResetGarbage_fallsThroughToRetryAfter() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-reset", "abc", "retry-after", "15");
        long now = 1_000L;
        assertEquals(now + 15_000L, AnthropicRequestTranslator.parseResetMs(h, now));
    }

    @Test
    void parseResetMs_retryAfterOnly() {
        Map<String, String> h = headers("retry-after", "42");
        long now = 9_000L;
        assertEquals(now + 42_000L, AnthropicRequestTranslator.parseResetMs(h, now));
    }

    @Test
    void parseResetMs_neitherHeader_returnsNull_backoffFallbackIntentionallyDropped() {
        assertNull(AnthropicRequestTranslator.parseResetMs(headers(), 0L));
    }

    @Test
    void parseResetMs_negativeUnifiedReset_andNoRetryAfter_returnsNull() {
        Map<String, String> h = headers("anthropic-ratelimit-unified-reset", "-5");
        assertNull(AnthropicRequestTranslator.parseResetMs(h, 0L));
    }
}
