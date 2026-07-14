package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline deterministic parity tests for {@link ClaudeHandleOrchestrator}, checked against
 * claude-code-auth's actual {@code handle} (src/driver/index.ts:72-177): the control flow was
 * reconstructed verbatim into a throwaway Node harness (module-level deps that stay TS --
 * {@code manager}/{@code proxyManager}/{@code getMaxAttempts}/{@code getAutoCandidates} --
 * injected via closures instead of the real imports) and driven with a scripted fake {@code
 * fetch} plus recording fakes for {@code manager}, executed with {@code node} (v26.3.1, same as
 * T6a) to snapshot the exact ordered call sequences and final response shapes asserted below --
 * not hand-derived from reading the TS alone. See task-6b-report.md for the harness.
 */
class ClaudeHandleOrchestratorTest {

    private final JsonCodec json = new TestJsonCodec();

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static ClaudeHandleOrchestrator.RequestInputs inputs() {
        ClaudeHandleOrchestrator.RequestInputs in = new ClaudeHandleOrchestrator.RequestInputs();
        in.url = "https://loader.local/v1/messages";
        in.method = "POST";
        in.headers = headers();
        in.bodyText = "{\"model\":\"claude-code-sonnet\",\"messages\":[]}";
        in.ctxModel = null;
        in.topAutoCandidate = null;
        return in;
    }

    private static ClaudeHandleOrchestrator.OrchestratorConfig cfg(int maxAttempts) {
        ClaudeHandleOrchestrator.OrchestratorConfig c = new ClaudeHandleOrchestrator.OrchestratorConfig();
        c.maxAttempts = maxAttempts;
        return c;
    }

    private ClaudeHandleOrchestrator orchestrator(long nowMillis) {
        return new ClaudeHandleOrchestrator(json, () -> nowMillis);
    }

    /** Records every {@link ClaudeHandleOrchestrator.AccountOps} call as one formatted line, in order. */
    private static final class RecordingAccountOps implements ClaudeHandleOrchestrator.AccountOps {
        final List<String> calls = new ArrayList<>();
        final Deque<ClaudeHandleOrchestrator.Acquired> acquireQueue = new ArrayDeque<>();
        int enabledCount;

        @Override
        public ClaudeHandleOrchestrator.Acquired acquire(String lane) {
            ClaudeHandleOrchestrator.Acquired next = acquireQueue.poll();
            calls.add("acquire(" + lane + ") -> " + (next == null ? "null" : next.accountId + "/" + next.access));
            return next;
        }

        @Override
        public void reportError(String accountId, int attempt, String message) {
            calls.add("reportError(" + accountId + "," + attempt + ",\"" + message + "\")");
        }

        @Override
        public void reportRateLimit(String accountId, String lane, Long resetMs) {
            calls.add("reportRateLimit(" + accountId + "," + lane + "," + resetMs + ")");
        }

        @Override
        public void reportSuccess(String accountId) {
            calls.add("reportSuccess(" + accountId + ")");
        }

        @Override
        public void disable(String accountId, String reason) {
            calls.add("disable(" + accountId + ",\"" + reason + "\")");
        }

        @Override
        public int listEnabledCount() {
            return enabledCount;
        }

        @Override
        public void captureQuota(String accountId, Map<String, String> headers) {
            calls.add("captureQuota(" + accountId + "," + headers + ")");
        }
    }

    /** Scripted {@link ClaudeHandleOrchestrator.AttemptExecutor}: pops one canned result per call. */
    private static final class ScriptedExecutor implements ClaudeHandleOrchestrator.AttemptExecutor {
        final Deque<ClaudeHandleOrchestrator.AttemptResult> results = new ArrayDeque<>();
        final List<String> accountIdsSeen = new ArrayList<>();

        @Override
        public ClaudeHandleOrchestrator.AttemptResult execute(String accountId, AnthropicRequestTranslator.PreparedRequest prepared) {
            accountIdsSeen.add(accountId);
            return results.poll();
        }
    }

    // ---- scenarios (snapshotted from the Node harness, see task-6b-report.md) -----------------

    @Test
    void happyPath_okOnAttempt0_reportSuccessAndServe() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(200, headers(), false, "ref0"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE, decision.kind);
        assertEquals("ref0", decision.attemptRef);
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{})",
                "reportSuccess(acc1)"
        ), accounts.calls);
    }

    @Test
    void rateLimitThenOk_rotatesAccount_reportsParsedResetMs_thenServes() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 2;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc2", "tok2"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(429,
                headers("anthropic-ratelimit-unified-reset", "1700000100"), false, "ref0"));
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(200, headers(), false, "ref1"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE, decision.kind);
        assertEquals("ref1", decision.attemptRef);
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{anthropic-ratelimit-unified-reset=1700000100})",
                "reportRateLimit(acc1,messages,1700000100000)",
                "acquire(messages) -> acc2/tok2",
                "captureQuota(acc2,{})",
                "reportSuccess(acc2)"
        ), accounts.calls);
    }

    @Test
    void unauthorizedThenForbiddenThenOk_disablesBeforeNextAcquire() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 3;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc2", "tok2"));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc3", "tok3"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(401, headers(), false, "ref0"));
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(403, headers(), false, "ref1"));
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(200, headers(), false, "ref2"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals("ref2", decision.attemptRef);
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{})",
                "reportError(acc1,0,\"401 unauthorized\")",
                "acquire(messages) -> acc2/tok2",
                "captureQuota(acc2,{})",
                "disable(acc2,\"re-login required (token lacks inference scope)\")",
                "reportError(acc2,1,\"403 scope\")",
                "acquire(messages) -> acc3/tok3",
                "captureQuota(acc3,{})",
                "reportSuccess(acc3)"
        ), accounts.calls);
        // disable must precede the NEXT acquire (index.ts:159 disables before rotating away).
        int disableIdx = accounts.calls.indexOf("disable(acc2,\"re-login required (token lacks inference scope)\")");
        int nextAcquireIdx = accounts.calls.indexOf("acquire(messages) -> acc3/tok3");
        assertTrue(disableIdx < nextAcquireIdx);
    }

    @Test
    void missingAccess_reportsErrorAndRotates_withoutExecutingAnAttempt() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", null));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(200, headers(), false, "ref0"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals("ref0", decision.attemptRef);
        assertEquals(List.of(
                "acquire(messages) -> acc1/null",
                "reportError(acc1,0,\"missing access token\")",
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{})",
                "reportSuccess(acc1)"
        ), accounts.calls);
        assertEquals(1, exec.accountIdsSeen.size()); // no attempt executed for the access-less acquire
    }

    @Test
    void noEnabledAccount_terminalChatError400() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 0;
        // acquireQueue left EMPTY -- Deque.poll() on an empty queue returns null, matching the TS
        // `!acquired` case (ArrayDeque disallows a literal null element).
        ScriptedExecutor exec = new ScriptedExecutor();

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, decision.kind);
        assertEquals(400, decision.status);
        assertEquals("1", decision.headers.get("x-hub-chat-error"));
        assertEquals("application/json", decision.headers.get("content-type"));
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":" +
                "\"No Claude account available — all accounts are disabled or logged out. Run `cc auth` to add or re-enable one.\"}}",
                decision.body);
    }

    @Test
    void someCoolingButEnabled_retryable503() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        // acquireQueue left EMPTY, see noEnabledAccount_terminalChatError400 above.
        ScriptedExecutor exec = new ScriptedExecutor();

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, decision.kind);
        assertEquals(503, decision.status);
        assertFalse(decision.headers.containsKey("x-hub-chat-error"));
        assertEquals("{\"error\":{\"message\":\"No Claude account free right now (all rate-limited). Try again shortly.\"}}",
                decision.body);
    }

    @Test
    void exhaustionAfterAll429_servesLastResponse_notSynthetic() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(429,
                headers("anthropic-ratelimit-unified-reset", "1700000100"), false, "ref0"));
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(429,
                headers("anthropic-ratelimit-unified-reset", "1700000200"), false, "ref1"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(2), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE, decision.kind);
        assertEquals("ref1", decision.attemptRef); // the LAST attempt's real 429, not a synthesized error
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{anthropic-ratelimit-unified-reset=1700000100})",
                "reportRateLimit(acc1,messages,1700000100000)",
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{anthropic-ratelimit-unified-reset=1700000200})",
                "reportRateLimit(acc1,messages,1700000200000)"
        ), accounts.calls);
    }

    @Test
    void exhaustionWithZeroSuccessfulFetches_syntheticError502() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(0, null, true, null));
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(0, null, true, null));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(2), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, decision.kind);
        assertEquals(502, decision.status);
        assertEquals("{\"error\":{\"message\":\"Claude request failed after 2 attempts\"}}", decision.body);
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "reportError(acc1,0,\"transport failed\")",
                "acquire(messages) -> acc1/tok1",
                "reportError(acc1,1,\"transport failed\")"
        ), accounts.calls);
    }

    @Test
    void nonRetryableUpstreamError_surfacedAsIs_noFurtherReportCalls() {
        RecordingAccountOps accounts = new RecordingAccountOps();
        accounts.enabledCount = 1;
        accounts.acquireQueue.add(new ClaudeHandleOrchestrator.Acquired("acc1", "tok1"));
        ScriptedExecutor exec = new ScriptedExecutor();
        exec.results.add(new ClaudeHandleOrchestrator.AttemptResult(400, headers(), false, "ref0"));

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator(0).handle(inputs(), cfg(4), exec, accounts);

        assertEquals(ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE, decision.kind);
        assertEquals("ref0", decision.attemptRef);
        assertEquals(List.of(
                "acquire(messages) -> acc1/tok1",
                "captureQuota(acc1,{})"
        ), accounts.calls);
    }
}
