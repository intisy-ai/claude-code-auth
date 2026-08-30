package io.github.intisy.ai.js;

import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.claude.AnthropicRequestTranslator;
import io.github.intisy.ai.claude.ClaudeHandleOrchestrator;
import io.github.intisy.ai.claude.ClaudeModelRouting;
import io.github.intisy.ai.claude.ClaudeQuotaParser;
import io.github.intisy.ai.claude.IrJsonCodecAdapter;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.seam.NoopLogger;
import io.github.intisy.ai.seam.SimpleJsonCodec;

import org.teavm.jso.JSExport;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JS export surface over claude-code-auth's Java: the request rewrite, the quota rules, the
 * model routing and the whole attempt loop.
 *
 * <p>Every export calls straight into the JVM-side classes, so each is ONE Java method compiled
 * twice, by javac for {@code :claude-provider}'s jar and by TeaVM for this bundle. There is no
 * second implementation anywhere: {@code src/driver} reaches all of it through here.
 *
 * @implNote {@code io.github.intisy.ai.js.surface.ClaudeProviderSurface} declares what this exports
 * for a TypeScript consumer, because JSPromise, JSString and the three JSO functors mean nothing to
 * one. This class lives in the same package as the shared {@code :js-base} seam so
 * {@code SimpleJsonCodec} is referenced unqualified rather than duplicated.
 */
public final class ClaudeProviderJs {
    private ClaudeProviderJs() {
    }

    /**
     * The beta header this provider must send, merged with whatever the caller already set.
     *
     * @param existing the caller's own beta header, which may be empty
     * @return the merged header value
     */
    @JSExport
    public static String mergeBeta(String existing) {
        return AnthropicRequestTranslator.mergeBeta(existing);
    }

    /**
     * Exercises the IR-based system-block injection under TeaVM: decodes an Anthropic request
     * body through core-ir's {@link AnthropicTranslator}, applies
     * {@link AnthropicRequestTranslator#ensureClaudeCodeSystemBlocks}, and re-encodes it, proving
     * core-ir's translator (and this module's :ir dependency) is itself transpilable, not just
     * {@code AnthropicRequestTranslator}'s own pure functions.
     *
     * @param bodyJson the request body, as JSON
     * @return the body with the system prompt applied, as JSON
     */
    @JSExport
    public static String ensureClaudeCodeSystemJson(String bodyJson) {
        JsonCodec json = new SimpleJsonCodec();
        AnthropicTranslator translator = new AnthropicTranslator(new IrJsonCodecAdapter(json));
        IrRequest ir = translator.decodeRequest(bodyJson);
        ir.system = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(ir.system);
        return translator.encodeRequest(ir);
    }

    /**
     * What a surface shows for one quota bucket.
     *
     * @param bucket the bucket's id
     * @return its display label
     */
    @JSExport
    public static String poolLabel(String bucket) {
        return ClaudeQuotaParser.poolLabel(bucket);
    }

    /**
     * Whether an account has quota left to serve a request.
     *
     * @param accountJson the stored account, as JSON
     * @return true when at least one pool has capacity
     */
    @JSExport
    public static boolean accountHasQuota(String accountJson) {
        ClaudeQuotaParser parser = new ClaudeQuotaParser(new SimpleJsonCodec());
        return parser.accountHasQuotaJson(accountJson);
    }

    /**
     * Which quota bucket a usage limit belongs to.
     *
     * @param limitJson one entry of the usage endpoint's limits array, as JSON
     * @return the bucket's id, or null when the limit names no bucket
     */
    @JSExport
    public static String bucketOfLimit(String limitJson) {
        ClaudeQuotaParser parser = new ClaudeQuotaParser(new SimpleJsonCodec());
        return parser.bucketOfLimitJson(limitJson);
    }

    // ---- ClaudeModelRouting + ClaudeHandleOrchestrator ---------------------------------------------

    /**
     * Whether a status means the upstream rate-limited the request.
     *
     * @param status the HTTP status
     * @return true when it is a rate limit rather than another failure
     */
    @JSExport
    public static boolean isRateLimitStatus(int status) {
        return ClaudeModelRouting.isRateLimitStatus(status);
    }

    /**
     * Which model an automatic selection resolves to.
     *
     * @param bodyJson the request body, as JSON
     * @param ctxModel the model the router assigned, or empty
     * @param topAutoCandidate the leaderboard's first choice, which stays the host's to rank
     * @return the body naming a concrete model, as JSON
     */
    @JSExport
    public static String resolveAutoModel(String bodyJson, String ctxModel, String topAutoCandidate) {
        return ClaudeModelRouting.resolveAutoModel(new SimpleJsonCodec(), bodyJson, ctxModel, topAutoCandidate);
    }

    /**
     * The request body with the assigned model written into it.
     *
     * @param bodyJson the request body, as JSON
     * @param ctxModel the model the router assigned, or empty
     * @return the body naming the model it will be served as, as JSON
     */
    @JSExport
    public static String applyAssignedModel(String bodyJson, String ctxModel) {
        return ClaudeModelRouting.applyAssignedModel(new SimpleJsonCodec(), bodyJson, ctxModel,
                NoopLogger.INSTANCE);
    }

    /**
     * The upstream model list mapped into the shape a surface lists.
     *
     * @param modelsJson what the upstream answered, as JSON
     * @return the models as a JSON object keyed by model id
     */
    @JSExport
    public static String fetchModelsMapping(String modelsJson) {
        return ClaudeModelRouting.fetchModelsMapping(new SimpleJsonCodec(), modelsJson);
    }

    /**
     * Constructs a {@link ClaudeHandleOrchestrator} and drives one full {@code handle()} call
     * through it with an always-terminal {@link ClaudeHandleOrchestrator.AccountOps} fake (no
     * enabled accounts) -- exercises the orchestrator class itself (construction, the retry loop's
     * first iteration, the synthetic-400 branch, and the {@link JsonCodec}-backed body builders)
     * under TeaVM, proving the whole state machine -- not just the pure {@code ClaudeModelRouting}
     * helpers above -- is transpilable.
     *
     * @return the synthesized JSON body the orchestrator answers a no-account request with
     */
    @JSExport
    public static String handleNoAccountSmokeTest() {
        ClaudeHandleOrchestrator orchestrator = new ClaudeHandleOrchestrator(new SimpleJsonCodec(), System::currentTimeMillis);

        ClaudeHandleOrchestrator.RequestInputs in = new ClaudeHandleOrchestrator.RequestInputs();
        in.url = "/v1/messages";
        in.method = "POST";
        in.headers = new java.util.LinkedHashMap<>();
        in.bodyText = "{\"model\":\"claude-code-sonnet\"}";

        ClaudeHandleOrchestrator.OrchestratorConfig cfg = new ClaudeHandleOrchestrator.OrchestratorConfig();
        cfg.maxAttempts = 4;

        ClaudeHandleOrchestrator.AttemptExecutor exec = (accountId, prepared) -> {
            throw new IllegalStateException("no account should ever be acquired in this smoke test");
        };
        ClaudeHandleOrchestrator.AccountOps accounts = new ClaudeHandleOrchestrator.AccountOps() {
            @Override
            public ClaudeHandleOrchestrator.Acquired acquire(String lane) {
                return null;
            }

            @Override
            public void reportError(String accountId, String lane, int attempt, String message) {
            }

            @Override
            public void reportRateLimit(String accountId, String lane, Long resetMs) {
            }

            @Override
            public void reportSuccess(String accountId) {
            }

            @Override
            public void disable(String accountId, String reason) {
            }

            @Override
            public int listEnabledCount() {
                return 0;
            }

            @Override
            public void captureQuota(String accountId, java.util.Map<String, String> headers) {
            }
        };

        ClaudeHandleOrchestrator.HandleDecision decision = orchestrator.handle(in, cfg, exec, accounts);
        return decision.body;
    }

    // ---- the production async entry (two-@Async composition) ---------------------------------------

    /**
     * Runs the FULL {@link ClaudeHandleOrchestrator#handle} decision loop with host transport +
     * account rotation supplied as JS async/sync callbacks, and surfaces the whole
     * (repeatedly-suspending) call graph to JS as ONE {@code Promise}. Inside the loop, EACH
     * attempt suspends first on {@link JsAccountOpsBridge#acquire} (async) then on
     * {@link JsAttemptExecutorBridge#execute} (async): two DISTINCT {@code @Async} bridges
     * composing in one TeaVM CPS-transformed call graph. Built by hand as a {@code JSPromise}
     * over a thread reaching the {@code @Async} boundaries (identical to {@code
     * CoreProxyJs.routeJsonAsync}), not {@code JSPromise.callAsync}, whose generic {@code
     * resolve.accept} would leak a raw {@code jl_String} instead of a real JS string (see
     * {@code JsHttpClientBridge.JsHttpSend}).
     *
     * @param inputsJson {@code {url, method, headers:{}, bodyText, ctxModel?, topAutoCandidate?}}
     * @param configJson {@code {maxAttempts, defaultCooldownSeconds?, maxCooldownSeconds?}} (the
     *                   lane is the fixed {@code "messages"} constant; the cooldown pair drives the
     *                   no-reset-header exponential backoff, defaulting to settings.ts's 60/900)
     * @param jsExec     async attempt transport ({@code fetch}+IP-proxy in prod)
     * @param jsAcquire  async {@code manager.acquire(lane)}
     * @param jsReports  the grouped synchronous account-reporting callbacks
     * @return a {@code Promise<string>} resolving with the {@link ClaudeHandleOrchestrator.HandleDecision}
     *         serialized as {@code {kind:"SERVE", attemptRef}} or
     *         {@code {kind:"SYNTHETIC", status, headers, body}}
     */
    @JSExport
    public static JSPromise<JSString> handleClaudeRequestAsync(
            String inputsJson,
            String configJson,
            JsAttemptExecutorBridge.JsExecFn jsExec,
            JsAccountOpsBridge.JsAcquireFn jsAcquire,
            JsAccountOpsBridge.JsReportFns jsReports) {
        return new JSPromise<>((resolve, reject) -> new Thread(() -> {
            try {
                JsonCodec json = new SimpleJsonCodec();
                ClaudeHandleOrchestrator orchestrator =
                        new ClaudeHandleOrchestrator(json, System::currentTimeMillis);

                ClaudeHandleOrchestrator.RequestInputs in = parseInputs(json, inputsJson);
                ClaudeHandleOrchestrator.OrchestratorConfig cfg = parseConfig(json, configJson);
                ClaudeHandleOrchestrator.AttemptExecutor exec = new JsAttemptExecutorBridge(jsExec, json);
                ClaudeHandleOrchestrator.AccountOps accounts = new JsAccountOpsBridge(jsAcquire, jsReports, json);

                // transitively async: handle() -> acquire() and execute() both suspend at @Async
                ClaudeHandleOrchestrator.HandleDecision decision = orchestrator.handle(in, cfg, exec, accounts);

                resolve.accept(JSString.valueOf(decisionToJson(json, decision)));
            } catch (Throwable e) {
                reject.accept(JSString.valueOf("handleClaudeRequestAsync failed: " + e));
            }
        }).start());
    }

    @SuppressWarnings("unchecked")
    private static ClaudeHandleOrchestrator.RequestInputs parseInputs(JsonCodec json, String inputsJson) {
        ClaudeHandleOrchestrator.RequestInputs in = new ClaudeHandleOrchestrator.RequestInputs();
        Object parsed = inputsJson != null ? json.parse(inputsJson) : null;
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            in.url = asString(m.get("url"));
            in.method = asString(m.get("method"));
            in.bodyText = asString(m.get("bodyText"));
            in.ctxModel = asString(m.get("ctxModel"));
            in.topAutoCandidate = asString(m.get("topAutoCandidate"));
            Map<String, String> headers = new LinkedHashMap<>();
            Object headersVal = m.get("headers");
            if (headersVal instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) headersVal).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
            in.headers = headers;
        } else {
            in.headers = new LinkedHashMap<>();
        }
        return in;
    }

    private static ClaudeHandleOrchestrator.OrchestratorConfig parseConfig(JsonCodec json, String configJson) {
        ClaudeHandleOrchestrator.OrchestratorConfig cfg = new ClaudeHandleOrchestrator.OrchestratorConfig();
        cfg.maxAttempts = 1;
        Object parsed = configJson != null ? json.parse(configJson) : null;
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            Object maxAttempts = m.get("maxAttempts");
            if (maxAttempts instanceof Number) cfg.maxAttempts = ((Number) maxAttempts).intValue();
            // Cooldown config for the no-reset-header exponential backoff. Absent -> OrchestratorConfig's
            // settings.ts-matching defaults (60/900) stand.
            Object defaultCooldown = m.get("defaultCooldownSeconds");
            if (defaultCooldown instanceof Number) cfg.defaultCooldownSeconds = ((Number) defaultCooldown).intValue();
            Object maxCooldown = m.get("maxCooldownSeconds");
            if (maxCooldown instanceof Number) cfg.maxCooldownSeconds = ((Number) maxCooldown).intValue();
        }
        return cfg;
    }

    private static String decisionToJson(JsonCodec json, ClaudeHandleOrchestrator.HandleDecision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (decision.kind == ClaudeHandleOrchestrator.HandleDecision.Kind.SERVE) {
            out.put("kind", "SERVE");
            out.put("attemptRef", decision.attemptRef); // opaque; whatever JS supplied (number/String)
        } else {
            out.put("kind", "SYNTHETIC");
            out.put("status", decision.status);
            out.put("headers", decision.headers);
            out.put("body", decision.body);
        }
        return json.stringify(out);
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
