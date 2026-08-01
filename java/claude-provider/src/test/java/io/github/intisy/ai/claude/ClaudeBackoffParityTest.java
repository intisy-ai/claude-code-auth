package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.select.RateLimitMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Frozen fixture for the exponential-backoff reset time {@link ClaudeHandleOrchestrator} applies
 * to a rate-limited response with no reset header: {@code now + RateLimitMath.calculateBackoffMs
 * (attempt, baseMs, maxMs, false)}, base/max derived from claude-code-auth's
 * defaultCooldownSeconds/maxCooldownSeconds (60/900) the same way the orchestrator does. Values
 * are captured epoch-ms for attempts 0..5, including the cap kicking in once the doubling exceeds
 * maxCooldownSeconds (attempts 4 and 5 both land at the 900s ceiling).
 */
class ClaudeBackoffParityTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long BASE_MS = 60L * 1000L;
    private static final long MAX_MS = 900L * 1000L;

    private static final long[] EXPECTED_RESET_MS = {
            1_700_000_060_000L,
            1_700_000_120_000L,
            1_700_000_240_000L,
            1_700_000_480_000L,
            1_700_000_900_000L,
            1_700_000_900_000L,
    };

    @Test
    void resetMsMatchesFrozenValuesForAttempts0to5() {
        for (int attempt = 0; attempt < EXPECTED_RESET_MS.length; attempt++) {
            long resetMs = NOW + RateLimitMath.calculateBackoffMs(attempt, BASE_MS, MAX_MS, false);
            assertEquals(EXPECTED_RESET_MS[attempt], resetMs, "attempt " + attempt);
        }
    }
}
