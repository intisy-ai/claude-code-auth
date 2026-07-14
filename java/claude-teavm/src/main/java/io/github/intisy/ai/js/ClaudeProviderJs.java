package io.github.intisy.ai.js;

import io.github.intisy.ai.claude.AnthropicRequestTranslator;
import io.github.intisy.ai.claude.ClaudeQuotaParser;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.jso.JSExport;

/**
 * TeaVM JS export surface over claude-code-auth's Java port (T6a) -- proves
 * {@code AnthropicRequestTranslator} and {@code ClaudeQuotaParser} are TeaVM-transpilable
 * ({@code generateJavaScript} green), mirroring stub-auth's {@code StubProviderJs} pattern. Lives
 * in the SAME package ({@code io.github.intisy.ai.js}) as core-proxy's {@code :teavm} module (a
 * Gradle project dependency, see {@code claude-teavm/build.gradle}), so {@code SimpleJsonCodec}
 * is referenced unqualified exactly like {@code CoreProxyJs}/{@code StubProviderJs} do -- NOT
 * duplicated here.
 *
 * <p>Every export below calls straight into the JVM-side ported classes -- ONE Java method,
 * compiled twice (javac for {@code :claude-provider}'s jar, TeaVM for this module) -- so this is
 * a thin touch-surface, not a reimplementation.
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
}
