package io.github.intisy.ai.js;

import io.github.intisy.ai.claude.AnthropicRequestTranslator;
import io.github.intisy.ai.claude.ClaudeHandleOrchestrator;
import io.github.intisy.ai.claude.ClaudeModelRouting;
import io.github.intisy.ai.claude.ClaudeQuotaParser;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.jso.JSExport;

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
     * Exercises {@link AnthropicRequestTranslator#ensureClaudeCodeSystem} plus the
     * {@link JsonCodec} SPI (parse the body, inject the identity block, stringify it back).
     */
    @JSExport
    public static String ensureClaudeCodeSystemJson(String bodyJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = bodyJson != null ? json.parse(bodyJson) : null;
        Object updated = AnthropicRequestTranslator.ensureClaudeCodeSystem(parsed);
        return json.stringify(updated);
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
}
