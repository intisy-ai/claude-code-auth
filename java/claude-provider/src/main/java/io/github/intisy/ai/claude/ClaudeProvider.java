package io.github.intisy.ai.claude;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandleIrException;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelCatalogProvider;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.routing.OAuthProvider;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.QuotaProvider;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *
 * <p>Typed capability SPI (E-C): {@link #handle} now carries ONLY the messages orchestrator --
 * the {@code GET/PUT /v1/config}, {@code GET /v1/models}, {@code GET /v1/quota}, and {@code
 * GET/POST /v1/oauth/*} URL branches that used to live here are retired in favor of the typed
 * {@link ConfigurableProvider}/{@link ModelCatalogProvider}/{@link QuotaProvider}/{@link
 * OAuthProvider} methods below, each a thin delegate to the same {@link ClaudeConfig}/{@link
 * ClaudeModelsFetch}/{@link ClaudeUsageFetch}/{@link ClaudeOAuth} classes as before (no duplicated
 * logic). This is JVM/server-facing only -- the TeaVM JS export surface ({@code ClaudeProviderJs})
 * never referenced this class and is untouched; the TS driver keeps its own native config/models/
 * quota/oauth paths.
 *
 * <p>SP-3 T2 adds {@link #handleIr}, the IR-native alternative to {@link #handle} (see its own
 * javadoc below): purely additive, coexisting with the unchanged {@link #handle}/{@link
 * #materialize} passthrough above until a later task (T4) removes the legacy wire path.
 */
public final class ClaudeProvider implements Provider, ConfigurableProvider, ModelCatalogProvider,
        QuotaProvider, OAuthProvider {

    public static final String ID = "claude-code-auth";

    // One orchestrator per backend (memoized): the orchestrator is stateless across requests --
    // every per-request value (inputs/config/seams) is a handle() parameter, not ctor state.
    private static final ConcurrentHashMap<ClaudeBackend, ClaudeHandleOrchestrator> ORCHESTRATORS =
            new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    // ---- ConfigurableProvider -----------------------------------------------------------------

    @Override
    public ConfigSchema configSchema(HandlerCtx ctx) {
        return ClaudeConfig.schema();
    }

    @Override
    public Map<String, Object> getConfigValues(HandlerCtx ctx) {
        return ClaudeConfig.values(ClaudeBackend.forCtx(ctx));
    }

    @Override
    public Map<String, Object> putConfigValues(HandlerCtx ctx, Map<String, Object> values) {
        return ClaudeConfig.putValues(ClaudeBackend.forCtx(ctx), values);
    }

    // ---- ModelCatalogProvider -----------------------------------------------------------------

    @Override
    public List<ModelInfo> models(HandlerCtx ctx) {
        return ClaudeModelsFetch.models(ClaudeBackend.forCtx(ctx));
    }

    // ---- QuotaProvider ------------------------------------------------------------------------

    @Override
    public List<AccountQuota> quota(HandlerCtx ctx) {
        return ClaudeUsageFetch.quota(ClaudeBackend.forCtx(ctx));
    }

    // ---- OAuthProvider ------------------------------------------------------------------------

    @Override
    public AuthorizeInfo authorize(HandlerCtx ctx) {
        return ClaudeOAuth.authorizeInfo();
    }

    @Override
    public Map<String, Object> exchange(HandlerCtx ctx, String body) {
        return ClaudeOAuth.exchangeValues(ClaudeBackend.forCtx(ctx), body);
    }

    // ---- messages orchestrator ----------------------------------------------------------------

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        ClaudeBackend backend = ClaudeBackend.forCtx(ctx);
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

    // ---- SP-3 T2: IR-native alternative to handle() -------------------------------------------

    /**
     * IR-native alternative to {@link #handle} (SP-3 T2): encodes the inbound {@link IrRequest}
     * to Anthropic wire text and runs it through the SAME {@link ClaudeHandleOrchestrator} flow
     * as {@link #handle} (retry/rotation/rate-limit backoff unchanged), then decodes a genuine
     * 2xx upstream response back to the canonical IR.
     *
     * <p>Only a real 2xx {@code SERVE} is decoded through {@link AnthropicTranslator#decodeResponse}
     * -- a {@code SYNTHETIC} decision's body (see {@code ClaudeHandleOrchestrator#chatErrorBody}/
     * {@code #errorResponseBody}) and a non-2xx {@code SERVE} (a genuine upstream error, "surface
     * as-is") are NOT guaranteed to be Anthropic MESSAGE-shaped JSON -- forcing either through the
     * message codec would corrupt it (the codec always injects {@code id}/{@code content}/
     * {@code model}/{@code stop_reason} keys that a bare error envelope never had). Both cases
     * throw {@link HandleIrException} instead (T3c-2), core-proxy's canonical typed transport
     * error, carrying the real status/headers/body: {@code Router.route} catches it and
     * reconstructs an equivalent {@code HttpResponse}, running it through the SAME rate-limit/
     * fallback logic a legacy {@link #handle} response would get, instead of collapsing to a flat
     * 502 (see {@code HandleIrException}'s own javadoc).
     *
     * <p>The JVM {@link ClaudeHostSeams.HostAttemptExecutor} always fully reads the upstream body
     * into a {@code String} (no true SSE streaming on this path today -- see {@link #handle}'s own
     * javadoc and {@code ClaudeProviderTest}, which never exercises a streaming body), so this
     * method only ever returns a buffered {@link IrResponse}, never an event stream.
     */
    @Override
    public IrResponse handleIr(IrRequest request, HandlerCtx ctx) throws Exception {
        ClaudeBackend backend = ClaudeBackend.forCtx(ctx);
        Logger log = loggerFor(ctx);
        ClaudeHandleOrchestrator orchestrator = orchestratorFor(backend);

        AnthropicTranslator translator = new AnthropicTranslator(new IrJsonCodecAdapter(backend.json));
        String bodyText = translator.encodeRequest(request);

        ClaudeHandleOrchestrator.RequestInputs in = new ClaudeHandleOrchestrator.RequestInputs();
        // No wire request exists on this entry point (the caller already decoded one into `request`
        // before calling handleIr) -- url/method/headers default exactly like an absent inbound
        // request would (prepareClaudeRequest falls back to POST /v1/messages with no extra headers).
        in.url = null;
        in.method = null;
        in.headers = null;
        in.bodyText = bodyText;
        in.ctxModel = ctx != null ? ctx.model : null;
        in.topAutoCandidate = null;
        in.log = log;

        ClaudeHandleOrchestrator.OrchestratorConfig cfg = new ClaudeHandleOrchestrator.OrchestratorConfig();
        cfg.maxAttempts = Math.max(1, countEnabledAccounts(backend));

        ClaudeHandleOrchestrator.AttemptExecutor exec = new ClaudeHostSeams.HostAttemptExecutor(backend);
        ClaudeHandleOrchestrator.AccountOps accounts = new ClaudeHostSeams.HostAccountOps(backend);

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator.handle(in, cfg, exec, accounts);
        return decodeServedIrResponse(decision, translator);
    }

    private static IrResponse decodeServedIrResponse(ClaudeHandleOrchestrator.HandleDecision decision,
            AnthropicTranslator translator) throws HandleIrException {
        if (decision.kind == ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE
                && decision.attemptRef instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) decision.attemptRef;
            if (response.status >= 200 && response.status < 300) {
                return translator.decodeResponse(response.body != null ? response.body : "");
            }
            // Real upstream non-2xx (rate limit, bad request, etc.) -- carry the exact
            // status/headers/body through the canonical typed transport error (T3c-2) so
            // core-proxy's front door (Router.route) can reconstruct it and run the SAME
            // rate-limit/fallback logic a legacy handle() response would get.
            throw new HandleIrException(response.status, response.headers, response.body);
        }
        if (decision.kind == ClaudeHandleOrchestrator.HandleDecision.Kind.SYNTHETIC) {
            // The orchestrator's own synthetic error (no-account, exhaustion, etc.) -- same typed
            // error, carrying the status/body this provider already synthesizes.
            throw new HandleIrException(decision.status, decision.headers, decision.body);
        }
        throw new IllegalStateException("claude handleIr: unrecognized decision kind " + decision.kind);
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

    private static HttpResponse errorResponse(int status, String errorType, String message) {
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
