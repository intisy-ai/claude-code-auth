import * as claude from "../generated/claude-orchestrator.teavm.js";

/**
 * The seam onto this provider's transpiled Java, which owns every decision it makes about a
 * request, a model or an account's quota.
 *
 * @remarks
 * Statically imported. The bundle is inlined into each deployed entry anyway, so deferring it saves
 * no bytes, and evaluating it costs **24.7 ms** (measured 2026-08-30 against the 842 KB bundle),
 * which is not worth a promise on every caller that has to answer synchronously.
 *
 * Values cross as JSON text, which is the shape an account, a limit and a request body already have.
 */

/** One rate-limit pool, as the account's cache stores it. */
export interface QuotaPool {
  /** How much of the pool is used, from 0 to 1 or beyond. */
  utilization?: number;
  /** When it resets, in epoch milliseconds. */
  reset?: number | null;
  /** What the upstream called the pool's state. */
  status?: string;
}

/** One entry of the usage endpoint's limits array. */
export interface UsageLimit {
  /** The limit's kind, `session` or `weekly_all`. */
  kind?: string;
  /** The group it belongs to, `weekly` for a weekly bucket. */
  group?: string;
  /** What it is scoped to, when it is scoped to one model. */
  scope?: { model?: { id?: string; display_name?: string } };
  /** How much of it is used, as a percentage. */
  percent?: number;
  /** When it resets, as a timestamp. */
  resets_at?: string;
  /** How serious the upstream considers the usage. */
  severity?: string;
}

/**
 * The canonical bucket key for one usage limit, aligned with the header bucket names so both
 * sources describe the same pools.
 *
 * @param limit - one entry of the usage endpoint's limits array
 * @returns the bucket key, or null when the limit names no bucket
 */
export function bucketOfLimit(limit: UsageLimit | null | undefined): string | null {
  return claude.bucketOfLimit(JSON.stringify(limit ?? null));
}

/**
 * What a surface shows for one bucket.
 *
 * @param bucket - the bucket key
 * @returns its label, or the key itself when it names no known shape
 */
export function poolLabel(bucket: string): string {
  return claude.poolLabel(bucket);
}

/**
 * Whether an account has quota left in any pool.
 *
 * @param account - the account, whose cached quota is read
 * @returns true when at least one pool has capacity; unknown counts as false
 */
export function accountHasQuota(account: unknown): boolean {
  return claude.accountHasQuota(JSON.stringify(account ?? null));
}

/**
 * Whether a status means the upstream rate-limited the request.
 *
 * @param status - the HTTP status
 * @returns true when it is a rate limit rather than another failure
 */
export function isRateLimitStatus(status: number): boolean {
  return claude.isRateLimitStatus(status);
}
