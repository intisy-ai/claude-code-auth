package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.manager.Acquired;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.Map;

/**
 * JVM host implementations of the two seams {@link ClaudeHandleOrchestrator} needs (Phase 6, see
 * {@code .superpowers/sdd/phase-6-brief.md}): adapts {@link ClaudeBackend}'s core-auth {@code
 * AccountManager}/{@code AccountStore} to {@code AccountOps}, and runs one attempt via {@code
 * HttpClient} as {@code AttemptExecutor}. Claude needs only these two seams -- unlike
 * antigravity-auth's seven (no RequestPreparer: the orchestrator builds the {@code
 * PreparedRequest} itself via {@code AnthropicRequestTranslator}; no model-cache/project seams:
 * Claude has no Gemini-style onboarding). No decision logic lives here, only host I/O + shape
 * adaptation.
 */
final class ClaudeHostSeams {

    private ClaudeHostSeams() {
    }

    // ---- Seam 1: AccountOps ------------------------------------------------------------------

    static final class HostAccountOps implements ClaudeHandleOrchestrator.AccountOps {
        private final ClaudeBackend backend;

        HostAccountOps(ClaudeBackend backend) {
            this.backend = backend;
        }

        @Override
        public ClaudeHandleOrchestrator.Acquired acquire(String lane) {
            Acquired acquired;
            try {
                acquired = backend.accounts.acquire(lane);
            } catch (RuntimeException e) {
                // A refresh failure (e.g. a revoked refresh token) already disabled the account
                // inside AccountManager -- fold it into the orchestrator's own "no account
                // available" contract (null) rather than throwing out of the DECISION loop.
                return null;
            }
            if (acquired == null || acquired.account == null) {
                return null;
            }
            return new ClaudeHandleOrchestrator.Acquired(acquired.account.id, acquired.access);
        }

        @Override
        public void reportError(String accountId, int attempt, String message) {
            backend.accounts.reportError(accountId, attempt, message);
        }

        @Override
        public void reportRateLimit(String accountId, String lane, Long resetMs) {
            // AccountManager.reportRateLimit takes a primitive long; the orchestrator's seam
            // param is a nullable Long -- guard null defensively (the orchestrator always
            // computes a fallback before calling this, but never rely on that from the seam).
            if (resetMs != null) {
                backend.accounts.reportRateLimit(accountId, lane, resetMs.longValue());
            }
        }

        @Override
        public void reportSuccess(String accountId) {
            backend.accounts.reportSuccess(accountId);
        }

        @Override
        public void disable(String accountId, String reason) {
            backend.accounts.mutate(accountId, a -> {
                a.enabled = Boolean.FALSE;
                a.disabledReason = reason;
            });
        }

        @Override
        public int listEnabledCount() {
            int count = 0;
            for (Account a : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
                if (a.enabled != Boolean.FALSE) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void captureQuota(String accountId, Map<String, String> headers) {
            // MVP no-op: quota capture/display is the later provider-management-UI sub-project.
            // The serve + rate-limit path does not need it (see phase-6 brief, decisions section).
        }
    }

    // ---- Seam 2: AttemptExecutor -------------------------------------------------------------

    /**
     * Runs ONE prepared request via {@link ClaudeBackend#http}. The retained {@link HttpResponse}
     * itself becomes the opaque {@code attemptRef} so a {@code SERVE} decision can return it
     * verbatim (native Anthropic passthrough).
     */
    static final class HostAttemptExecutor implements ClaudeHandleOrchestrator.AttemptExecutor {
        private final ClaudeBackend backend;

        HostAttemptExecutor(ClaudeBackend backend) {
            this.backend = backend;
        }

        @Override
        public ClaudeHandleOrchestrator.AttemptResult execute(
                String accountId, AnthropicRequestTranslator.PreparedRequest prepared) {
            HttpRequest req = new HttpRequest();
            req.method = prepared.method;
            req.url = prepared.request;
            req.headers = prepared.headers;
            req.body = prepared.body;
            HttpResponse resp;
            try {
                resp = backend.http.send(req);
            } catch (RuntimeException e) {
                return new ClaudeHandleOrchestrator.AttemptResult(0, null, true, null);
            }
            return new ClaudeHandleOrchestrator.AttemptResult(resp.status, resp.headers, false, resp);
        }
    }
}
