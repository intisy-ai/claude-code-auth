package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decision logic for serving a request. This class owns EVERY branch/retry/rotation decision: the pre-loop body rewrite, the retry
 * loop, the status-&gt;action branching, and the final-fallback choice. It performs NO I/O
 * itself: the actual {@code fetch}, the IP-proxy-pool selection/fallback/reporting, and the SSE
 * byte-stream pass-through all stay host-side, driven through the two injected interfaces below,
 * {@link AttemptExecutor} (transport) and {@link AccountOps} (account rotation/reporting). No
 * response BODY ever crosses into this class: on a servable outcome it returns {@link
 * HandleDecision#serve} carrying only the host's own opaque {@code attemptRef}; the host streams
 * that attempt's retained live response back to the client verbatim (SSE intact).
 *
 * <p>Uses only {@link JsonCodec} (body parse/stringify, via {@link ClaudeModelRouting} and
 * {@link AnthropicRequestTranslator}) and {@link Clock} (the TS driver's implicit {@code
 * Date.now()} inside {@code parseResetMs}'s retry-after branch, made an explicit constructor
 * dependency here purely for test determinism, mirroring {@code AnthropicRequestTranslator}'s
 * own {@code parseResetMs(headers, nowMillis)} design). Both are pre-existing core-proxy
 * {@code :routing} SPIs. No gson/java.net/java.nio/reflection/threads/System.getenv, so this
 * class is TeaVM-transpilable.
 */
public final class ClaudeHandleOrchestrator {

    /** Claude subscription limits are account-wide, so every request shares one lane. */
    public static final String LANE = "messages";

    // Exact wording -- matched verbatim by callers/fixtures.
    static final String NO_ACCOUNT_MESSAGE =
            "No Claude account available, all accounts are disabled or logged out. Run `cc auth` to add or re-enable one.";
    // Exact wording -- matched verbatim by callers/fixtures.
    static final String RATE_LIMITED_ALL_MESSAGE =
            "No Claude account free right now (all rate-limited). Try again shortly.";
    // Exact disabledReason string; callers may assert this verbatim.
    static final String DISABLED_REASON_403 = "re-login required (token lacks inference scope)";

    private static final Logger NOOP_LOGGER = msg -> { };

    private final JsonCodec json;
    private final Clock clock;

    public ClaudeHandleOrchestrator(JsonCodec json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    // ---- injected interfaces (host implements; TS now, Java server later in-process) -----------

    /**
     * Executes ONE attempt against the upstream: fetch + IP-proxy selection/fallback/reporting are
     * ALL host-internal here; the orchestrator never sees a proxy URL or a live {@code Response}.
     * Only the decision-relevant summary comes back.
     */
    public interface AttemptExecutor {
        AttemptResult execute(String accountId, AnthropicRequestTranslator.PreparedRequest prepared);
    }

    /** Result of one {@link AttemptExecutor#execute} call. */
    public static final class AttemptResult {
        public final int status;
        public final Map<String, String> headers;
        /**
         * True when the host exhausted every transport strategy (proxy + direct retry) for this
         * attempt without ever getting a response (collapsed to one flag; the per-branch log/report
         * calls for "fetch failed"/"direct retry failed" with the real JS {@code Error} text stay
         * host-side, since only the host ever sees that error object; see {@link #handle} for how
         * this flag is surfaced to {@link AccountOps#reportError}).
         */
        public final boolean transportFailed;
        /** Opaque host-supplied handle the host uses to map back to its retained live response. */
        public final Object attemptRef;

        public AttemptResult(int status, Map<String, String> headers, boolean transportFailed, Object attemptRef) {
            this.status = status;
            this.headers = headers;
            this.transportFailed = transportFailed;
            this.attemptRef = attemptRef;
        }
    }

    /**
     * Account rotation/reporting, maps 1:1 onto core-auth's {@code AccountManager}. This
     * orchestrator only DECLARES the interface and calls it in the right order; it never
     * implements it (parity tests supply a recording fake).
     */
    public interface AccountOps {
        /** {@code manager.acquire(lane)}. {@code null} &lt;=&gt; the TS's {@code !acquired || !acquired.account}. */
        Acquired acquire(String lane);

        void reportError(String accountId, int attempt, String message);

        void reportRateLimit(String accountId, String lane, Long resetMs);

        void reportSuccess(String accountId);

        /** {@code manager.mutate(accountId, a => { a.enabled = false; a.disabledReason = reason; })}. */
        void disable(String accountId, String reason);

        /** {@code manager.list().filter(a => a.enabled !== false).length}. */
        int listEnabledCount();

        /** {@code captureQuota(manager, accountId, headers)}, called on every non-transport-failed response. */
        void captureQuota(String accountId, Map<String, String> headers);
    }

    /** {@code {account: {id}, access}} as returned by {@code manager.acquire}. */
    public static final class Acquired {
        public final String accountId;
        public final String access;

        public Acquired(String accountId, String access) {
            this.accountId = accountId;
            this.access = access;
        }
    }

    // ---- request/config shapes -------------------------------------------------------------------

    /** Everything {@code handle(request, ctx)} needed from the inbound request + router ctx. */
    public static final class RequestInputs {
        public String url;
        public String method;
        public Map<String, String> headers;
        /** {@code await request.clone().text()}, already read by the host; {@code null} on a read failure. */
        public String bodyText;
        /** {@code ctx.model} -- the router's assigned model for this request. */
        public String ctxModel;
        /**
         * {@code getAutoCandidates("claude-code")[0]}; the leaderboard stays TS, so the value is
         * passed in here. {@code null}/empty means "no ranking yet".
         */
        public String topAutoCandidate;
        /** {@code ctx.log}; {@code null} defaults to a no-op, matching the TS {@code || (() => {})}. */
        public Logger log;
    }

    /** Per-request tunables the host re-reads fresh each call. */
    public static final class OrchestratorConfig {
        /** {@code getMaxAttempts()}, read per-request so a config edit applies without a restart. */
        public int maxAttempts;
        /**
         * {@code getDefaultCooldownSeconds()}, base seconds for the exponential backoff applied to
         * a 429/529 that carries NO reset header. Doubles per attempt. Defaults mirror the TS
         * settings' DEFAULT_COOLDOWN_SECONDS/MAX_COOLDOWN_SECONDS so a config that omits them still
         * backs off exactly like the TS path rather than collapsing to "reset = now".
         */
        public int defaultCooldownSeconds = 60;
        /** {@code getMaxCooldownSeconds()}, cap the doubling backoff can grow to. */
        public int maxCooldownSeconds = 900;
    }

    // ---- HandleDecision -----------------------------------------------------------------------

    /**
     * The orchestrator's only output: either serve an already-executed attempt's live response
     * verbatim ({@link Kind#SERVE}), or synthesize one (the rare terminal/exhaustion paths --
     * {@link Kind#SYNTHETIC}). No response BYTES ever flow through this type in the SERVE case --
     * only the host's own opaque {@code attemptRef}, which the host maps back to its retained
     * {@code Response} and streams (SSE intact).
     */
    public static final class HandleDecision {
        public enum Kind { SERVE, SYNTHETIC }

        public final Kind kind;
        /** Set only for {@link Kind#SERVE}. */
        public final Object attemptRef;
        /** Set only for {@link Kind#SYNTHETIC}. */
        public final int status;
        /** Set only for {@link Kind#SYNTHETIC}. */
        public final Map<String, String> headers;
        /** Set only for {@link Kind#SYNTHETIC}. */
        public final String body;

        private HandleDecision(Kind kind, Object attemptRef, int status, Map<String, String> headers, String body) {
            this.kind = kind;
            this.attemptRef = attemptRef;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public static HandleDecision serve(Object attemptRef) {
            return new HandleDecision(Kind.SERVE, attemptRef, 0, null, null);
        }

        public static HandleDecision synthetic(int status, Map<String, String> headers, String body) {
            return new HandleDecision(Kind.SYNTHETIC, null, status, headers, body);
        }
    }

    // ---- the state machine ---------------------------------------------------------------------

    /**
     * Implements {@code handle}'s decision flow: pre-loop body prep, then the retry loop over
     * {@code cfg.maxAttempts}, branching on each attempt's outcome in the same order as the TS
     * driver: rate-limit before 401 before 403 before success before "surface as-is".
     */
    public HandleDecision handle(RequestInputs in, OrchestratorConfig cfg, AttemptExecutor exec, AccountOps accounts) {
        Logger log = in.log != null ? in.log : NOOP_LOGGER;

        String bodyText = ClaudeModelRouting.resolveAutoModel(json, in.bodyText, in.ctxModel, in.topAutoCandidate);
        bodyText = ClaudeModelRouting.applyAssignedModel(json, bodyText, in.ctxModel, log);

        AnthropicRequestTranslator.RequestInit init = new AnthropicRequestTranslator.RequestInit();
        init.method = in.method;
        init.headers = in.headers;
        init.body = bodyText;

        int maxAttempts = cfg.maxAttempts;
        Object lastRef = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Acquired acquired = accounts.acquire(LANE);
            if (acquired == null || acquired.accountId == null || acquired.accountId.isEmpty()) {
                int enabled = accounts.listEnabledCount();
                // No ENABLED account at all -> retrying can never help, so a TERMINAL 400 (chatError);
                // accounts merely cooling down keep the retryable 503.
                return enabled == 0
                        ? HandleDecision.synthetic(400, chatErrorHeaders(), chatErrorBody(NO_ACCOUNT_MESSAGE))
                        : HandleDecision.synthetic(503, plainJsonHeaders(), errorResponseBody(RATE_LIMITED_ALL_MESSAGE));
            }

            String accountId = acquired.accountId;
            String access = acquired.access;
            if (access == null || access.isEmpty()) {
                accounts.reportError(accountId, attempt, "missing access token");
                continue;
            }

            AnthropicRequestTranslator.PreparedRequest prepared;
            try {
                prepared = AnthropicRequestTranslator.prepareClaudeRequest(json, in.url, init, access);
            } catch (RuntimeException e) {
                log.log("prepare failed: " + e);
                accounts.reportError(accountId, attempt, String.valueOf(e));
                continue;
            }

            AttemptResult result = exec.execute(accountId, prepared);

            if (result.transportFailed) {
                // Collapsed: the host already exhausted proxy + direct fetch and logged/reported
                // the real per-branch error text itself (it alone has the Error object); this is
                // the orchestrator's own bookkeeping call so account rotation still sees a
                // reportError per failed attempt.
                accounts.reportError(accountId, attempt, "transport failed");
                continue;
            }

            // Every non-transport-failed response, before any status branch.
            accounts.captureQuota(accountId, result.headers);

            if (ClaudeModelRouting.isRateLimitStatus(result.status)) {
                lastRef = result.attemptRef;
                Long resetMs = AnthropicRequestTranslator.parseResetMs(result.headers, clock.now());
                // AnthropicRequestTranslator.parseResetMs deliberately does not implement the
                // exponential-backoff fallback (it needs the cooldown config), returning null when
                // neither the unified nor the retry-after header is present. The TS path falls
                // through to that backoff in that case, so replicate it inline here (see
                // computeBackoffResetMs) to keep reportRateLimit consistent across paths.
                if (resetMs == null) resetMs = computeBackoffResetMs(attempt, cfg);
                accounts.reportRateLimit(accountId, LANE, resetMs);
                continue; // rotate account
            }

            if (result.status == 401) {
                lastRef = result.attemptRef;
                accounts.reportError(accountId, attempt, "401 unauthorized");
                continue;
            }

            if (result.status == 403) {
                lastRef = result.attemptRef;
                // Exact disabledReason string.
                accounts.disable(accountId, DISABLED_REASON_403);
                accounts.reportError(accountId, attempt, "403 scope");
                continue;
            }

            if (result.status >= 200 && result.status < 300) { // Fetch API's `response.ok`
                accounts.reportSuccess(accountId);
                return HandleDecision.serve(result.attemptRef);
            }

            return HandleDecision.serve(result.attemptRef); // non-retryable upstream error -- surface as-is
        }

        // Serve the REAL upstream response (e.g. a genuine 429 with Anthropic's rate-limit
        // headers) when one exists, rather than masking it with a synthetic error.
        return lastRef != null
                ? HandleDecision.serve(lastRef)
                : HandleDecision.synthetic(502, plainJsonHeaders(),
                        errorResponseBody("Claude request failed after " + maxAttempts + " attempts"));
    }

    /**
     * Exponential-backoff reset time (epoch ms) for a rate-limited response with NO reset header:
     * <pre>Date.now() + Math.min(getDefaultCooldownSeconds()*1000 * 2^attempt, getMaxCooldownSeconds()*1000)</pre>
     * Computed in {@code double} to mirror JS number arithmetic exactly, then floored to a long
     * epoch-ms; {@code Clock} supplies "now" (JS {@code Date.now()}) for test determinism.
     *
     * <p>core-auth's {@code RateLimitMath.calculateBackoffMs} is the CANONICAL backoff-math home,
     * but its formula intentionally DIFFERS from this one (it adds random jitter by default and
     * returns a relative DURATION, not an absolute epoch), so it cannot be reused here without
     * breaking parity with the TS {@code handle} path, hence the inline replication.
     */
    private long computeBackoffResetMs(int attempt, OrchestratorConfig cfg) {
        double raw = (double) cfg.defaultCooldownSeconds * 1000.0 * Math.pow(2, attempt);
        double capped = Math.min(raw, (double) cfg.maxCooldownSeconds * 1000.0);
        return clock.now() + (long) capped;
    }

    // ---- synthetic response bodies ---------------------------------------------------------------

    private Map<String, String> plainJsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        return h;
    }

    private Map<String, String> chatErrorHeaders() {
        Map<String, String> h = plainJsonHeaders();
        h.put("x-hub-chat-error", "1");
        return h;
    }

    private String errorResponseBody(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return json.stringify(body);
    }

    private String chatErrorBody(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "invalid_request_error");
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", error);
        return json.stringify(body);
    }
}
