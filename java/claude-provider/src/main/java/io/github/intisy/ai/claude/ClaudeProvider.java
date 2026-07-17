package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 of the JVM {@code ClaudeProvider} (see {@code .superpowers/sdd/phase-6-brief.md}):
 * {@link #handle} runs every request through the already-ported {@link ClaudeHandleOrchestrator}
 * -- retry/account-rotation/rate-limit backoff all come from the orchestrator's DECISION loop.
 * This class only (1) builds/memoizes one orchestrator per {@link ClaudeBackend} (Claude's
 * orchestrator ctor takes only {@code json}/{@code clock}; the two host seams are passed
 * per-call into {@code handle}, not baked into the ctor -- a structural difference from
 * antigravity's ~11-arg seam-baking ctor), (2) builds the orchestrator's {@code RequestInputs}
 * from the incoming {@code HttpRequest}/{@code HandlerCtx}, and (3) materializes the returned
 * {@code HandleDecision} into an {@code HttpResponse}.
 *
 * <p>Claude is native Anthropic (no Gemini format bridge, no response transform): a {@code SERVE}
 * decision returns the retained upstream {@code HttpResponse} verbatim, and a {@code SYNTHETIC}
 * decision's body is already final Anthropic error JSON -- neither is re-wrapped here.
 *
 * <p>Shape discipline: {@code compileOnly project(":routing")} + {@code compileOnly
 * "io.github.intisy:jvm:0.1.0"} keep this module's own jar THIN (no {@code :routing}/{@code
 * :jvm} classes bundled -- the host's {@code ProviderRegistry} classloader already has them).
 */
public final class ClaudeProvider implements Provider {

    public static final String ID = "claude-code-auth";

    // One orchestrator per backend (memoized): the orchestrator is stateless across requests --
    // every per-request value (inputs/config/seams) is a handle() parameter, not ctor state.
    private static final ConcurrentHashMap<ClaudeBackend, ClaudeHandleOrchestrator> ORCHESTRATORS =
            new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        // Model-map Task 1: dashboard discovery calls GET .../v1/models -- a side path that never
        // touches the messages orchestrator below (no retry/rotation, just one ensureAccess'd
        // upstream fetch). Checked first so the messages path stays completely untouched.
        if (request != null && "GET".equalsIgnoreCase(request.method) && request.url != null
                && request.url.endsWith("/v1/models")) {
            return ClaudeModelsFetch.fetch(ClaudeBackend.forConfigDir(ctx != null ? ctx.configDir : null), ctx);
        }

        // Quota-display Task 1: dashboard usage-display calls GET .../v1/quota -- same side-path
        // discipline as the /v1/models branch above (no retry/rotation, per-account ensureAccess'd
        // upstream fetches). Checked before the messages path so it stays completely untouched.
        if (request != null && "GET".equalsIgnoreCase(request.method) && request.url != null
                && request.url.endsWith("/v1/quota")) {
            return ClaudeUsageFetch.fetch(ClaudeBackend.forConfigDir(ctx != null ? ctx.configDir : null), ctx);
        }

        // Provider-authorize OAuth convention (Task 4): GET /v1/config, GET /v1/oauth/authorize,
        // POST /v1/oauth/exchange. Same side-path discipline as /v1/models and /v1/quota above --
        // checked before the messages orchestrator so that path stays completely untouched.
        // ClaudeOAuth is self-contained (JVM-only crypto + its own constants); config()/authorize()
        // need no backend at all, and exchange() only needs backend.http/json/clock.
        if (request != null && "GET".equalsIgnoreCase(request.method) && request.url != null
                && request.url.endsWith("/v1/config")) {
            return ClaudeOAuth.config();
        }
        if (request != null && "GET".equalsIgnoreCase(request.method) && request.url != null
                && request.url.endsWith("/v1/oauth/authorize")) {
            return ClaudeOAuth.authorize();
        }
        if (request != null && "POST".equalsIgnoreCase(request.method) && request.url != null
                && request.url.endsWith("/v1/oauth/exchange")) {
            ClaudeBackend oauthBackend = ClaudeBackend.forConfigDir(ctx != null ? ctx.configDir : null);
            return ClaudeOAuth.exchange(oauthBackend, request.body);
        }

        ClaudeBackend backend = ClaudeBackend.forConfigDir(ctx != null ? ctx.configDir : null);
        Logger log = loggerFor(ctx);
        ClaudeHandleOrchestrator orchestrator = orchestratorFor(backend);

        ClaudeHandleOrchestrator.RequestInputs in = new ClaudeHandleOrchestrator.RequestInputs();
        // The orchestrator computes the real api.anthropic.com URL itself inside
        // prepareClaudeRequest; the inbound request.url is the loader-facing path/URL, passed
        // through unchanged.
        in.url = request != null ? request.url : null;
        in.method = request != null ? request.method : null;
        in.headers = request != null ? request.headers : null;
        in.bodyText = request != null ? request.body : null;
        in.ctxModel = ctx != null ? ctx.model : null;
        in.topAutoCandidate = null; // no ranking leaderboard wired for the Claude lane yet
        in.log = log;

        ClaudeHandleOrchestrator.OrchestratorConfig cfg = new ClaudeHandleOrchestrator.OrchestratorConfig();
        // Rotate through every enabled account once per request (phase-6 decision: maxAttempts =
        // max(1, enabled account count)); cooldowns stay at the field defaults (60/900).
        cfg.maxAttempts = Math.max(1, countEnabledAccounts(backend));

        ClaudeHandleOrchestrator.AttemptExecutor exec = new ClaudeHostSeams.HostAttemptExecutor(backend);
        ClaudeHandleOrchestrator.AccountOps accounts = new ClaudeHostSeams.HostAccountOps(backend);

        try {
            return materialize(orchestrator.handle(in, cfg, exec, accounts));
        } catch (Throwable e) {
            return errorResponse(502, "api_error", "claude request failed: " + e.getMessage());
        }
    }

    private static ClaudeHandleOrchestrator orchestratorFor(ClaudeBackend backend) {
        return ORCHESTRATORS.computeIfAbsent(backend, b -> new ClaudeHandleOrchestrator(b.json, b.clock));
    }

    // Mirrors ClaudeHostSeams.HostAccountOps#listEnabledCount(): maxAttempts must rotate through
    // ENABLED accounts only, not every stored account (a disabled one would burn an attempt for
    // nothing).
    private static int countEnabledAccounts(ClaudeBackend backend) {
        int count = 0;
        for (Account a : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (a.enabled != Boolean.FALSE) {
                count++;
            }
        }
        return count;
    }

    // ---- HandleDecision materialization -- only TWO kinds ------------------------------------

    private static HttpResponse materialize(ClaudeHandleOrchestrator.HandleDecision d) {
        switch (d.kind) {
            case SERVE:
                // Native Anthropic passthrough: the retained upstream HttpResponse is already
                // the final answer -- return it verbatim, no transform.
                if (d.attemptRef instanceof HttpResponse) {
                    return (HttpResponse) d.attemptRef;
                }
                return errorResponse(502, "api_error", "empty serve reference");
            case SYNTHETIC:
                // The orchestrator's synthetic bodies are ALREADY final Anthropic error JSON --
                // do NOT re-wrap.
                HttpResponse response = new HttpResponse();
                response.status = d.status;
                response.headers = d.headers;
                response.body = d.body;
                return response;
            default:
                return errorResponse(502, "api_error", "unrecognized claude decision: " + d.kind);
        }
    }

    private static Logger loggerFor(HandlerCtx ctx) {
        Logger log = ctx != null ? ctx.log : null;
        return log != null ? log : (msg -> { });
    }

    // Package-private (not private) so ClaudeModelsFetch's own error paths can build the exact
    // same {"type":"error","error":{...}} shape instead of duplicating the JSON construction.
    static HttpResponse errorResponse(int status, String errorType, String message) {
        HttpResponse response = new HttpResponse();
        response.status = status;
        response.headers = new LinkedHashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{\"type\":\"error\",\"error\":{\"type\":" + quote(errorType) + ",\"message\":" + quote(message) + "}}";
        return response;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
