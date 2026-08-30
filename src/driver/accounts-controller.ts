// Claude's AccountController: provider-owned status + Verify / Refresh actions on
// top of basekit/auth's generic list/enable/remove helper.

import { accountControllerFromManager, verifyAllAccounts, refreshAccountToken, hasCapacity } from "@intisy-ai/basekit/auth";
import { ANTHROPIC_API_BASE, ANTHROPIC_OAUTH_BETA, ANTHROPIC_VERSION, CLAUDE_CODE_SYSTEM } from "../constants.js";
import { login } from "./login.js";
import { accountHasQuota, bucketOfLimit, poolLabel, type QuotaPool, type UsageLimit } from "./java.js";
import type { AccountManager, AccountQuota, CoreAccount } from "@intisy-ai/basekit/auth";

/**
 * A stored account as this provider augments it.
 *
 * @remarks
 * `cachedQuota` is this provider's own state on a core account: the pools it last saw, so the Quota
 * view shows real usage without a dedicated quota API.
 */
type ClaudeAccount = CoreAccount & {
  cachedQuota?: {
    pools?: Record<string, QuotaPool>;
    fiveHour?: QuotaPool;
    sevenDay?: QuotaPool;
    at?: number;
  };
};

/** Re-exported so the request path reaches every account rule through this one module. */
export { accountHasQuota };

function out(message: string): void {
  process.stdout.write(message + "\n");
}

// --- Quota (subscription rate-limit pools) ----------------------------------
// Anthropic returns unified rate-limit headers on EVERY response, one pool per
// bucket (5h, 7d, and any Anthropic adds later, e.g. a per-model weekly bucket
// for Fable), each with utilization (0..>1), reset (epoch s) and status. Buckets
// are DISCOVERED from the header names, never hardcoded, so new pools appear
// automatically. We capture them per account so the Quota view shows real usage
// without a dedicated quota API.
const UNIFIED_POOL_HEADER = /^anthropic-ratelimit-unified-(.+)-(utilization|reset|status)$/;

function readPools(headers: Headers): Record<string, QuotaPool> {
  const pools: Record<string, QuotaPool> = {};
  headers.forEach((value: string, name: string) => {
    const m = UNIFIED_POOL_HEADER.exec(String(name).toLowerCase());
    if (!m) return;   // ignores the bucketless "…-unified-reset" lane-timing header
    const pool = pools[m[1]] || (pools[m[1]] = {});
    if (m[2] === "utilization") { const v = parseFloat(value); if (!Number.isNaN(v)) pool.utilization = v; }
    else if (m[2] === "reset") { const v = parseInt(value, 10); if (!Number.isNaN(v)) pool.reset = v * 1000; }
    else if (value) pool.status = value;
  });
  for (const key of Object.keys(pools)) {
    if (typeof pools[key].utilization !== "number" && typeof pools[key].reset !== "number") delete pools[key];
  }
  return pools;
}

// Persist the pools captured from a response's headers onto the account. MERGES
// into the existing pools: headers only carry the buckets relevant to that request
// (a per-model weekly bucket appears only on requests to that model), so replacing
// wholesale would keep dropping pools the usage endpoint discovered.
/**
 * Persists the pools captured from a response's headers onto the account.
 *
 * @remarks
 * Merges rather than replaces: headers only carry the buckets relevant to that request, so a
 * per-model weekly bucket appears only on requests to that model, and writing wholesale would keep
 * dropping pools the usage endpoint discovered.
 *
 * @param manager - the account store to write through
 * @param accountId - the account the response was for
 * @param headers - the response's headers
 */
export function captureQuota(manager: AccountManager, accountId: string, headers: Headers): void {
  try {
    const pools = readPools(headers);
    if (!Object.keys(pools).length) return;
    manager.mutate(accountId, (account) => {
      const claude = account as ClaudeAccount;
      const prev = claude.cachedQuota?.pools ?? {};
      claude.cachedQuota = { pools: { ...prev, ...pools }, at: Date.now() };
    });
  } catch {}
}

// Authoritative pool list from the OAuth usage endpoint, the same source Claude
// Code's /usage screen reads. Unlike response headers it returns EVERY pool,
// including per-model weekly buckets (e.g. Fable), without needing a request to
// that model. Returns null on any failure (caller falls back to the header ping).
async function fetchUsagePools(access: string): Promise<Record<string, QuotaPool> | null> {
  const res = await fetch(ANTHROPIC_API_BASE + "/api/oauth/usage", {
    headers: { Authorization: "Bearer " + access, "anthropic-beta": ANTHROPIC_OAUTH_BETA, "anthropic-version": ANTHROPIC_VERSION },
  });
  if (!res.ok) return null;
  const data = await res.json();
  if (!data || !Array.isArray(data.limits)) return null;
  const pools: Record<string, QuotaPool> = {};
  for (const limit of data.limits as UsageLimit[]) {
    const bucket = bucketOfLimit(limit);
    if (!bucket || typeof limit.percent !== "number") continue;
    pools[bucket] = {
      utilization: limit.percent / 100,
      reset: limit.resets_at ? Date.parse(limit.resets_at) : null,
      status: limit.severity || undefined,
    };
  }
  return Object.keys(pools).length ? pools : null;
}

// Map the stored pools to basekit/auth's quota shape [{label, remainingFraction, resetTime}].
function claudeQuota(account: CoreAccount): AccountQuota[] | undefined {
  const q = (account as ClaudeAccount).cachedQuota;
  if (!q) return undefined;
  const pools: AccountQuota[] = [];
  const add = (pool: QuotaPool | undefined, label: string) => {
    if (!pool || typeof pool.utilization !== "number") return;
    const entry: AccountQuota = { label, remainingFraction: Math.max(0, Math.min(1, 1 - pool.utilization)) };
    if (typeof pool.reset === "number") entry.resetTime = pool.reset;
    pools.push(entry);
  };
  if (q.pools) for (const [bucket, pool] of Object.entries(q.pools).sort(([a], [b]) => a.localeCompare(b))) add(pool, poolLabel(bucket));
  else { add(q.fiveHour, "5-hour"); add(q.sevenDay, "7-day"); }   // pre-discovery cached shape
  return pools.length ? pools : undefined;
}

// On-demand refresh: the usage endpoint first (full, authoritative pool list,
// REPLACES the cache); fall back to a tiny max_tokens:1 ping whose response
// headers carry the request-relevant pools (merged into the cache).
async function refreshQuotaOne(manager: AccountManager, accountId: string): Promise<void> {
  const access = await manager.ensureAccess(accountId);
  if (!access) return;
  let pools: Record<string, QuotaPool> | null = null;
  try { pools = await fetchUsagePools(access); } catch {}
  if (pools) {
    manager.mutate(accountId, (account) => { (account as ClaudeAccount).cachedQuota = { pools, at: Date.now() }; });
    return;
  }
  const res = await fetch(ANTHROPIC_API_BASE + "/v1/messages", {
    method: "POST",
    headers: { Authorization: "Bearer " + access, "anthropic-version": ANTHROPIC_VERSION, "anthropic-beta": ANTHROPIC_OAUTH_BETA, "Content-Type": "application/json" },
    body: JSON.stringify({ model: "claude-haiku-4-5", max_tokens: 1, system: [{ type: "text", text: CLAUDE_CODE_SYSTEM }], messages: [{ role: "user", content: "ping" }] }),
  });
  captureQuota(manager, accountId, res.headers);
}

async function refreshQuotaAll(manager: AccountManager): Promise<void> {
  for (const account of manager.list()) {
    if (account.enabled === false) continue;
    try { await refreshQuotaOne(manager, account.id); } catch {}
  }
}

async function verify(manager: AccountManager, view: Pick<CoreAccount, "id" | "email">): Promise<void> {
  const name = view.email || view.id;
  try {
    const access = await manager.ensureAccess(view.id);
    if (!access) { out("✗ " + name + ": no access token"); return; }
    const aborter = new AbortController();
    const timer = setTimeout(() => aborter.abort(), 20000);
    let response: Response;
    try {
      response = await fetch(ANTHROPIC_API_BASE + "/v1/messages", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + access,
          "anthropic-version": ANTHROPIC_VERSION,
          "anthropic-beta": ANTHROPIC_OAUTH_BETA,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model: "claude-haiku-4-5",
          max_tokens: 1,
          system: [{ type: "text", text: CLAUDE_CODE_SYSTEM }],
          messages: [{ role: "user", content: "ping" }],
        }),
        signal: aborter.signal,
      });
    } finally {
      clearTimeout(timer);
    }
    if (response.status === 200 || response.status === 400 || response.status === 429) out("✓ " + name + ": verified");
    else if (response.status === 401) out("✗ " + name + ": token expired or revoked (401)");
    else if (response.status === 403) {
      // broken token (wrong scopes): disable + flag for re-login so it isn't used
      manager.mutate(view.id, (account) => { account.enabled = false; account.disabledReason = "re-login required (token lacks inference scope)"; });
      out("✗ " + name + ": disabled, re-login required (403 scope)");
    }
    else out("✗ " + name + ": " + response.status);
  } catch (error) {
    out("✗ " + name + ": " + (error instanceof Error ? error.message : String(error)));
  }
}

/**
 * The account controller this provider offers a host: status, quota, and the Verify and Refresh
 * actions, on top of basekit/auth's generic list, enable and remove.
 *
 * @param manager - the account store to act through
 * @returns the controller
 */
export function createClaudeAccounts(manager: AccountManager) {
  return accountControllerFromManager(manager, {
    // surface WHY the system disabled an account (e.g. 403 -> "re-login required").
    // Only disabledReason renders: cooldownReason holds transient raw error text
    // (e.g. "TypeError: fetch failed") that must never leak into the account row.
    detail: (account) => (account.enabled === false && account.disabledReason) ? account.disabledReason : undefined,
    quota: claudeQuota,
    refreshQuota: () => refreshQuotaAll(manager),
    refreshQuotaOne: (id) => refreshQuotaOne(manager, id),
    login: async () => {
      const account = await login({ log: (message) => process.stderr.write(message + "\n") });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
    // The narrowed manager verifyAllAccounts hands back has no ensureAccess, which verifying a
    // token needs, so the real one is closed over instead.
    actions: () => [{ label: "Verify all accounts", run: () => verifyAllAccounts(manager, (_narrowed, account) => verify(manager, account)) }],
    accountActions: (view) => [
      { label: "Verify access", run: () => verify(manager, view) },
      { label: "Refresh token", run: () => refreshAccountToken(manager, view) },
    ],
  });
}
