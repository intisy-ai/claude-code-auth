package io.github.intisy.ai.claude;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.claude.Fixtures.list;
import static io.github.intisy.ai.claude.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic parity tests for {@link AnthropicRequestTranslator}, checked against
 * claude-code-auth's actual {@code src/plugin/request.ts} behavior (the backoff-fallback branch
 * of {@code parseResetMs} is intentionally omitted here; see the class javadoc).
 *
 * <p>{@code prepareClaudeRequest_*} tests further down cover the {@code
 * ensureClaudeCodeSystemBlocks} wire shapes end to end (structurally, via {@code json.parse} on
 * the produced body, since the IR round trip can reorder JSON keys, never wire-significant).
 */
class AnthropicRequestTranslatorTest {

    private static final String CCS = AnthropicRequestTranslator.CLAUDE_CODE_SYSTEM;
    private final JsonCodec json = new TestJsonCodec();

    // ---- ensureClaudeCodeSystemBlocks -----------------------------------------------------

    private static List<Block> blocks(Block... b) {
        List<Block> l = new ArrayList<>();
        Collections.addAll(l, b);
        return l;
    }

    private static TextBlock text(String s) {
        return new TextBlock(s);
    }

    private static void assertIdentitySingleton(List<Block> result) {
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof TextBlock);
        assertEquals(CCS, ((TextBlock) result.get(0)).text);
    }

    @Test
    void ensureClaudeCodeSystemBlocks_nullSystem_injectsIdentityBlock() {
        // Covers the "absent"/"explicit null"/"non-string-non-array" cases at once:
        // AnthropicRequestCodec.decodeRequest decodes all three of those wire shapes to
        // the SAME `system == null`.
        assertIdentitySingleton(AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(null));
    }

    @Test
    void ensureClaudeCodeSystemBlocks_emptySystem_injectsIdentityBlock() {
        // Covers the "empty array" case (Array.isArray([]) === true in JS, but its first element
        // is undefined -> not the identity block -> prepended to nothing).
        assertIdentitySingleton(AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(blocks()));
    }

    @Test
    void ensureClaudeCodeSystemBlocks_singleIdentityBlock_dedupesToSingleBlock() {
        // Covers the "string exactly identity" case: a bare wire string always decodes to a
        // single TextBlock, indistinguishable at the Block level from a single-element wire
        // array carrying the same text.
        List<Block> result = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(blocks(text(CCS)));
        assertIdentitySingleton(result);
    }

    @Test
    void ensureClaudeCodeSystemBlocks_singleOtherBlock_prependsIdentityKeepingOriginalAsSecondBlock() {
        // Covers the "string other" case.
        List<Block> result = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(blocks(text("custom system prompt")));
        assertEquals(2, result.size());
        assertEquals(CCS, ((TextBlock) result.get(0)).text);
        assertEquals("custom system prompt", ((TextBlock) result.get(1)).text);
    }

    @Test
    void ensureClaudeCodeSystemBlocks_arrayAlreadyStartingWithIdentity_leftUntouched() {
        TextBlock identity = text(CCS);
        TextBlock extra = text("extra");
        List<Block> system = blocks(identity, extra);
        List<Block> result = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(system);
        assertSame(system, result);
        assertEquals(2, result.size());
        assertSame(identity, result.get(0));
        assertSame(extra, result.get(1));
    }

    @Test
    void ensureClaudeCodeSystemBlocks_identityWithCacheControl_preservedNotDropped() {
        // A genuine wire array can attach cache_control to the identity block itself (impossible
        // for a bare wire string) -- must survive verbatim, not be silently dropped by the
        // dedup-to-singleton rebuild.
        TextBlock identity = text(CCS);
        identity.cacheControl = "ephemeral";
        List<Block> result = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(blocks(identity));
        assertEquals(1, result.size());
        assertSame(identity, result.get(0));
        assertEquals("ephemeral", ((TextBlock) result.get(0)).cacheControl);
    }

    @Test
    void ensureClaudeCodeSystemBlocks_arrayNotStartingWithIdentity_identityPrepended() {
        TextBlock custom = text("custom");
        List<Block> result = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(blocks(custom));
        assertEquals(2, result.size());
        assertEquals(CCS, ((TextBlock) result.get(0)).text);
        assertSame(custom, result.get(1));
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
    void mergeBeta_substringMatch_leftUnchanged() {
        // mergeBeta uses a substring check (existing.contains(BETA)), not comma-list membership, so
        // "foo-oauth-2025-04-20-bar" is (arguably incorrectly) treated as already containing the flag.
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
        // "messages" included: AnthropicRequestCodec.encodeRequest always emits it (required by
        // the real Anthropic Messages API, so every genuine request already carries one) -- a
        // fixture omitting it would gain a new "messages":[] key on the IR round trip, which is
        // semantically correct (nothing was lost; there is no way to express "absent" vs "empty"
        // for a required array) but would fail this test's exact-map comparison.
        String body = json.stringify(map("model", "claude-x", "stream", true, "system", "hi", "messages", list()));
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
                map("model", "claude-x", "stream", true, "messages", list(), "system",
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

    // ---- prepareClaudeRequest system-block wire shapes (end to end through the IR round trip) ----

    @Test
    void prepareClaudeRequest_absentSystem_injectsIdentityBlock() {
        String body = json.stringify(map("model", "claude-x", "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-7");
        Object parsedBody = json.parse(result.body);
        assertEquals(list(map("type", "text", "text", CCS)), ((Map<?, ?>) parsedBody).get("system"));
    }

    @Test
    void prepareClaudeRequest_explicitNullSystem_injectsIdentityBlockOnly() {
        String body = json.stringify(map("model", "claude-x", "system", null, "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-8");
        Object parsedBody = json.parse(result.body);
        assertEquals(list(map("type", "text", "text", CCS)), ((Map<?, ?>) parsedBody).get("system"));
    }

    @Test
    void prepareClaudeRequest_nonStringNonArraySystem_replacedWithIdentityOnly() {
        String body = json.stringify(map("model", "claude-x", "system", 42L, "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-9");
        Object parsedBody = json.parse(result.body);
        assertEquals(list(map("type", "text", "text", CCS)), ((Map<?, ?>) parsedBody).get("system"));
    }

    @Test
    void prepareClaudeRequest_stringExactlyIdentity_dedupesToSingleBlock() {
        String body = json.stringify(map("model", "claude-x", "system", CCS, "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-10");
        Object parsedBody = json.parse(result.body);
        assertEquals(list(map("type", "text", "text", CCS)), ((Map<?, ?>) parsedBody).get("system"));
    }

    @Test
    void prepareClaudeRequest_arrayAlreadyStartingWithIdentity_leftUntouched() {
        Object system = list(map("type", "text", "text", CCS), map("type", "text", "text", "extra"));
        String body = json.stringify(map("model", "claude-x", "system", system, "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-11");
        Object parsedBody = json.parse(result.body);
        assertEquals(system, ((Map<?, ?>) parsedBody).get("system"));
    }

    @Test
    void prepareClaudeRequest_arrayNotStartingWithIdentity_identityPrepended() {
        Object system = list(map("type", "text", "text", "custom"));
        String body = json.stringify(map("model", "claude-x", "system", system, "messages", list()));
        AnthropicRequestTranslator.RequestInit init = init("POST", headers(), body);
        AnthropicRequestTranslator.PreparedRequest result =
                AnthropicRequestTranslator.prepareClaudeRequest(json, "/v1/messages", init, "acc-token-12");
        Object parsedBody = json.parse(result.body);
        assertEquals(
                list(map("type", "text", "text", CCS), map("type", "text", "text", "custom")),
                ((Map<?, ?>) parsedBody).get("system"));
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
