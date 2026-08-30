// Generated from Java sources. Do not edit.

/**
 * What the orchestrator tells the host about each attempt, as one object of synchronous callbacks.
 *
 * @remarks
 * Grouped into one object rather than passed as six functions, because the transpiled side
 * invokes them by name on the underlying JS object. A nullable reset time and the response headers
 * cross as JSON text, so the host reads them with `JSON.parse` and a missing reset is `"null"`.
 */
export interface ClaudeReportsShape {
  /**
   * Records what the upstream said about an account's remaining quota.
   *
   * @param accountId - the account the response was for
   * @param headersJson - the response headers, as a JSON object
   */
  captureQuota(accountId: string, headersJson: string): void;
  /**
   * Takes an account out of rotation.
   *
   * @param accountId - the account to disable
   * @param reason - why it is being disabled
   */
  disable(accountId: string, reason: string): void;
  /**
   * How many accounts are still in rotation, which decides whether another attempt is worth making.
   *
   * @returns the enabled account count
   */
  listEnabledCount(): number;
  /**
   * One attempt failed for a reason that is not a rate limit.
   *
   * @param accountId - the account that failed
   * @param lane - the lane it failed on
   * @param attempt - which attempt this was, counting from one
   * @param message - what went wrong
   */
  reportError(accountId: string, lane: string, attempt: number, message: string): void;
  /**
   * One attempt hit the upstream rate limit.
   *
   * @param accountId - the account that was limited
   * @param lane - the lane it was limited on
   * @param resetMsJson - when the limit resets, as JSON, or `"null"` when the upstream said nothing
   */
  reportRateLimit(accountId: string, lane: string, resetMsJson: string): void;
  /**
   * One attempt served the request.
   *
   * @param accountId - the account that served it
   */
  reportSuccess(accountId: string): void;
}

/**
 * Whether an account has quota left to serve a request.
 *
 * @param accountJson - the account, as JSON
 * @returns true when it can serve
 */
export declare function accountHasQuota(accountJson: string): boolean;
/**
 * The request body with the resolved model written into it.
 *
 * @param bodyJson - the request body, as JSON
 * @param ctxModel - the model the handler context named, or empty
 * @returns the body naming the model it will be served as, as JSON
 */
export declare function applyAssignedModel(bodyJson: string, ctxModel: string): string;
/**
 * Which quota bucket a rate-limit response belongs to.
 *
 * @param limitJson - the limit the upstream reported, as JSON
 * @returns the bucket's id
 */
export declare function bucketOfLimit(limitJson: string): string;
/**
 * The request body with this provider's own system prompt in place.
 *
 * @param bodyJson - the request body, as JSON
 * @returns the body with the system prompt applied, as JSON
 */
export declare function ensureClaudeCodeSystemJson(bodyJson: string): string;
/**
 * The upstream model list mapped into the shape a surface lists.
 *
 * @param modelsJson - what the upstream answered, as JSON
 * @returns the models, as a JSON object keyed by model id
 */
export declare function fetchModelsMapping(modelsJson: string): string;
/**
 * Runs the whole attempt loop: acquire an account, try it, report what happened, and either
 * serve or move on.
 *
 * @param inputsJson - the request's url, method, headers, body and model, as a JSON object
 * @param configJson - the attempt limit and the cooldown pair, as a JSON object
 * @param jsExec - the host's transport, taking an account id and a prepared request and answering
 * with the attempt's outcome as JSON
 * @param jsAcquire - the host's account rotation, taking a lane and answering with the account it
 * picked as JSON, or null when none is free
 * @param jsReports - what the loop tells the host about each attempt
 * @returns the decision as JSON: which attempt to serve, or a synthetic response to answer with
 */
export declare function handleClaudeRequestAsync(inputsJson: string, configJson: string, jsExec: ((a: string, b: string) => Promise<string>), jsAcquire: ClaudeAcquireFn, jsReports: ClaudeReportsShape): Promise<string>;
/**
 * The answer this provider gives when no account is configured at all.
 *
 * @returns the synthetic response, as JSON
 */
export declare function handleNoAccountSmokeTest(): string;
/**
 * Whether a status means the upstream rate-limited the request.
 *
 * @param status - the HTTP status
 * @returns true when it is a rate limit rather than another failure
 */
export declare function isRateLimitStatus(status: number): boolean;
/**
 * The beta header this provider must send, merged with whatever the caller already set.
 *
 * @param existing - the caller's own beta header, which may be empty
 * @returns the merged header value
 */
export declare function mergeBeta(existing: string): string;
/**
 * What a surface shows for one quota bucket.
 *
 * @param bucket - the bucket's id
 * @returns its display label
 */
export declare function poolLabel(bucket: string): string;
/**
 * Which model an automatic selection resolves to.
 *
 * @param bodyJson - the request body, as JSON
 * @param ctxModel - the model the handler context named, or empty
 * @param topAutoCandidate - the leaderboard's first choice, which stays the host's to rank
 * @returns the resolved model id
 */
export declare function resolveAutoModel(bodyJson: string, ctxModel: string, topAutoCandidate: string): string;

/** The host's account rotation, which answers with the account it picked or with nothing. */
export type ClaudeAcquireFn = (lane: string) => Promise<string | null>;

