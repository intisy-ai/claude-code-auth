package io.github.intisy.ai.js;

import io.github.intisy.ai.claude.AnthropicRequestTranslator;
import io.github.intisy.ai.claude.ClaudeHandleOrchestrator;
import io.github.intisy.ai.claude.ClaudeModelRouting;
import io.github.intisy.ai.claude.ClaudeQuotaParser;
import io.github.intisy.ai.claude.IrJsonCodecAdapter;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.jso.JSExport;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TeaVM JS export surface over claude-code-auth's Java port (T6a + T6b) -- proves {@code
 * AnthropicRequestTranslator}, {@code ClaudeQuotaParser}, {@code ClaudeModelRouting}, and {@code
 * ClaudeHandleOrchestrator} are ALL TeaVM-transpilable ({@code generateJavaScript} green),
 * mirroring stub-auth's {@code StubProviderJs} pattern. Lives in the SAME package ({@code
 * io.github.intisy.ai.js}) as core-proxy's {@code :teavm} module (a Gradle project dependency, see
 * {@code claude-teavm/build.gradle}), so {@code SimpleJsonCodec} is referenced unqualified exactly
 * like {@code CoreProxyJs}/{@code StubProviderJs} do -- NOT duplicated here.
 *
 * <p>Every export below calls straight into the JVM-side ported classes -- ONE Java method,
 * compiled twice (javac for {@code :claude-provider}'s jar, TeaVM for this module) -- so this is
 * a thin touch-surface, not a reimplementation. T6b does NOT wire this JS into claude-code-auth's
 * TS runtime (that's a later task) -- this module only proves transpilability.
 */
public final class ClaudeProviderJs {
    private ClaudeProviderJs() {
    }

    /** Exercises {@link AnthropicRequestTranslator#mergeBeta}. */
    @JSExport
    public static String mergeBeta(String existing) {
        return AnthropicRequestTranslator.mergeBeta(existing);
    }

    /**
     * Exercises the SP-2 IR-based system-block injection under TeaVM: decodes an Anthropic
     * request body through core-ir's {@link AnthropicTranslator}, applies
     * {@link AnthropicRequestTranslator#ensureClaudeCodeSystemBlocks}, and re-encodes it --
     * proving core-ir's translator (and this module's :ir dependency) is itself transpilable, not
     * just {@code AnthropicRequestTranslator}'s own pure functions. Supersedes the old
     * raw-JSON {@code ensureClaudeCodeSystemJson} export (its method was deleted).
     */
    @JSExport
    public static String ensureClaudeCodeSystemJson(String bodyJson) {
        JsonCodec json = new SimpleJsonCodec();
        AnthropicTranslator translator = new AnthropicTranslator(new IrJsonCodecAdapter(json));
        IrRequest ir = translator.decodeRequest(bodyJson);
        ir.system = AnthropicRequestTranslator.ensureClaudeCodeSystemBlocks(ir.system);
        return translator.encodeRequest(ir);
    }

    /** Exercises {@link ClaudeQuotaParser#poolLabel}. */
    @JSExport
    public static String poolLabel(String bucket) {
        return ClaudeQuotaParser.poolLabel(bucket);
    }

    /** Exercises {@link ClaudeQuotaParser#accountHasQuotaJson} (its {@link JsonCodec}-backed entry point). */
    @JSExport
    public static boolean accountHasQuota(String accountJson) {
        ClaudeQuotaParser parser = new ClaudeQuotaParser(new SimpleJsonCodec());
        return parser.accountHasQuotaJson(accountJson);
    }

    /** Exercises {@link ClaudeQuotaParser#bucketOfLimitJson}. */
    @JSExport
    public static String bucketOfLimit(String limitJson) {
        ClaudeQuotaParser parser = new ClaudeQuotaParser(new SimpleJsonCodec());
        return parser.bucketOfLimitJson(limitJson);
    }

    // ---- T6b: ClaudeModelRouting + ClaudeHandleOrchestrator ------------------------------------

    /** Exercises {@link ClaudeModelRouting#isRateLimitStatus}. */
    @JSExport
    public static boolean isRateLimitStatus(int status) {
        return ClaudeModelRouting.isRateLimitStatus(status);
    }

    /** Exercises {@link ClaudeModelRouting#resolveAutoModel} plus the {@link JsonCodec} SPI. */
    @JSExport
    public static String resolveAutoModel(String bodyJson, String ctxModel, String topAutoCandidate) {
        return ClaudeModelRouting.resolveAutoModel(new SimpleJsonCodec(), bodyJson, ctxModel, topAutoCandidate);
    }

    /** Exercises {@link ClaudeModelRouting#applyAssignedModel} (no-op logger). */
    @JSExport
    public static String applyAssignedModel(String bodyJson, String ctxModel) {
        return ClaudeModelRouting.applyAssignedModel(new SimpleJsonCodec(), bodyJson, ctxModel, msg -> {
        });
    }

    /** Exercises {@link ClaudeModelRouting#fetchModelsMapping}. */
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
     * helpers above -- is transpilable. Returns the synthesized JSON body.
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
            public void reportError(String accountId, int attempt, String message) {
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

    // ---- T6c1: the production async entry (two-@Async composition) -----------------------------

    /**
     * THE T6c1 export the later live-rewire task (T6c2) will call: runs the FULL
     * {@link ClaudeHandleOrchestrator#handle} decision loop with host transport + account rotation
     * supplied as JS async/sync callbacks, and surfaces the whole (repeatedly-suspending) call
     * graph to JS as ONE {@code Promise}. Inside the loop, EACH attempt suspends first on
     * {@link JsAccountOpsBridge#acquire} (async) then on {@link JsAttemptExecutorBridge#execute}
     * (async) -- two DISTINCT {@code @Async} bridges composing in one TeaVM CPS-transformed call
     * graph, the mechanism this task de-risks. Built by hand as a {@code JSPromise} over a thread
     * reaching the {@code @Async} boundaries (identical to {@code CoreProxyJs.routeJsonAsync}) --
     * not {@code JSPromise.callAsync}, whose generic {@code resolve.accept} would leak a raw
     * {@code jl_String} instead of a real JS string (see {@code JsHttpClientBridge.JsHttpSend}).
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
            // Cooldown config for the no-reset-header exponential backoff (request.ts:89-91). Absent
            // -> OrchestratorConfig's settings.ts-matching defaults (60/900) stand.
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
