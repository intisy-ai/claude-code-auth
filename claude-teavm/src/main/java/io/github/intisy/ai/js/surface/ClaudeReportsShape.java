package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * What the orchestrator tells the host about each attempt, as one object of synchronous callbacks.
 *
 * @implNote Grouped into one object rather than passed as six functions, because the transpiled side
 * invokes them by name on the underlying JS object. A nullable reset time and the response headers
 * cross as JSON text, so the host reads them with `JSON.parse` and a missing reset is `"null"`.
 */
@TsInterface
public interface ClaudeReportsShape {

    /**
     * One attempt failed for a reason that is not a rate limit.
     *
     * @param accountId the account that failed
     * @param lane the lane it failed on
     * @param attempt which attempt this was, counting from one
     * @param message what went wrong
     */
    void reportError(String accountId, String lane, int attempt, String message);

    /**
     * One attempt hit the upstream rate limit.
     *
     * @param accountId the account that was limited
     * @param lane the lane it was limited on
     * @param resetMsJson when the limit resets, as JSON, or `"null"` when the upstream said nothing
     */
    void reportRateLimit(String accountId, String lane, String resetMsJson);

    /**
     * One attempt served the request.
     *
     * @param accountId the account that served it
     */
    void reportSuccess(String accountId);

    /**
     * Takes an account out of rotation.
     *
     * @param accountId the account to disable
     * @param reason why it is being disabled
     */
    void disable(String accountId, String reason);

    /**
     * How many accounts are still in rotation, which decides whether another attempt is worth making.
     *
     * @return the enabled account count
     */
    int listEnabledCount();

    /**
     * Records what the upstream said about an account's remaining quota.
     *
     * @param accountId the account the response was for
     * @param headersJson the response headers, as a JSON object
     */
    void captureQuota(String accountId, String headersJson);
}
