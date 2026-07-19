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
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JVM {@code ClaudeProvider}: {@link #handleIr} runs every request through the already-ported
 * {@link ClaudeHandleOrchestrator} -- retry/account-rotation/rate-limit backoff all come from the
 * orchestrator's DECISION loop. This class only (1) builds/memoizes one orchestrator per {@link
 * ClaudeBackend} (Claude's orchestrator ctor takes only {@code json}/{@code clock}; the two host
 * seams are passed per-call, not baked into the ctor -- a structural difference from antigravity's
 * ~11-arg seam-baking ctor), (2) builds the orchestrator's {@code RequestInputs} from the inbound
 * {@link IrRequest} (encoded to Anthropic wire text via {@link AnthropicTranslator}), and (3)
 * decodes the served {@code HandleDecision} back to an {@link IrResponse}.
 *
 * <p>IR-native only (T4, the layering flip): the front-door (Router/proxy server) owns app&lt;-&gt;IR
 * translation, so this provider carries ZERO app-wire format code -- the legacy {@code handle}/
 * {@code materialize} wire passthrough is gone, and the class inherits {@link Provider}'s throwing
 * {@code handle} default. {@link #handleIr} decodes only a genuine 2xx {@code SERVE} through the
 * IR; every non-2xx or {@code SYNTHETIC} outcome throws {@link HandleIrException} carrying the
 * exact upstream status/headers/body, which the front-door reconstructs.
 *
 * <p>Shape discipline: {@code compileOnly project(":routing")} + {@code compileOnly
 * "io.github.intisy:jvm:0.1.0"} keep this module's own jar THIN (no {@code :routing}/{@code
 * :jvm} classes bundled -- the host's {@code ProviderRegistry} classloader already has them).
 *
 * <p>Typed capability SPI (E-C): the {@code GET/PUT /v1/config}, {@code GET /v1/models}, {@code
 * GET /v1/quota}, and {@code GET/POST /v1/oauth/*} handling lives in the typed {@link
 * ConfigurableProvider}/{@link ModelCatalogProvider}/{@link QuotaProvider}/{@link OAuthProvider}
 * methods below, each a thin delegate to the same {@link ClaudeConfig}/{@link ClaudeModelsFetch}/
 * {@link ClaudeUsageFetch}/{@link ClaudeOAuth} classes as before (no duplicated logic). This is
 * JVM/server-facing only -- the TeaVM JS export surface ({@code ClaudeProviderJs}) never referenced
 * this class and is untouched; the TS driver keeps its own native config/models/quota/oauth paths.
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

    // ---- messages orchestrator (IR-native serving path) ---------------------------------------

    /**
     * The IR-native serving path (T4): encodes the inbound {@link IrRequest} to Anthropic wire
     * text and runs it through the {@link ClaudeHandleOrchestrator} flow (retry/rotation/rate-limit
     * backoff), then decodes a genuine 2xx upstream response back to the canonical IR. The
     * front-door owns app&lt;-&gt;IR translation, so this provider never sees or emits the app's
     * wire format.
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
     * fallback logic instead of collapsing to a flat 502 (see {@code HandleIrException}'s own
     * javadoc).
     *
     * <p>The JVM {@link ClaudeHostSeams.HostAttemptExecutor} always fully reads the upstream body
     * into a {@code String} (no true SSE streaming on this path today -- {@code ClaudeProviderTest}
     * never exercises a streaming body), so this method only ever returns a buffered {@link
     * IrResponse}, never an event stream.
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

    private static Logger loggerFor(HandlerCtx ctx) {
        Logger log = ctx != null ? ctx.log : null;
        return log != null ? log : (msg -> { });
    }
}
