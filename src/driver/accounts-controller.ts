// @ts-nocheck
// Claude's AccountController: provider-owned status + Verify / Refresh actions on
// top of core-auth's generic list/enable/remove helper.

import { accountControllerFromManager } from "../../core-auth/dist/index.js";
import { ANTHROPIC_API_BASE, ANTHROPIC_OAUTH_BETA, ANTHROPIC_VERSION, CLAUDE_CODE_SYSTEM } from "../constants.js";
import { login } from "./login.js";

function out(message) {
  process.stdout.write(message + "\n");
}

// --- Quota (subscription rate-limit pools) ----------------------------------
// Anthropic returns unified rate-limit headers on EVERY response, one pool per
// bucket (5h, 7d, and any Anthropic adds later — e.g. a per-model weekly bucket
// for Fable), each with utilization (0..>1), reset (epoch s) and status. Buckets
// are DISCOVERED from the header names, never hardcoded, so new pools appear
// automatically. We capture them per account so the Quota view shows real usage
// without a dedicated quota API.
const UNIFIED_POOL_HEADER = /^anthropic-ratelimit-unified-(.+)-(utilization|reset|status)$/;

function readPools(headers) {
  const pools = {};
  headers.forEach((value, name) => {
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

// Persist the pools captured from a response's headers onto the account.
export function captureQuota(manager, accountId, headers) {
  try {
    const pools = readPools(headers);
    if (!Object.keys(pools).length) return;
    manager.mutate(accountId, (a) => { a.cachedQuota = { pools, at: Date.now() }; });
  } catch {}
}

// bucket key -> human label: "5h" -> "5-hour", "7d" -> "7-day"; a model-scoped
// bucket like "7d-fable" -> "7-day (Fable)"; anything unrecognized passes through.
function poolLabel(bucket) {
  const m = /^(\d+)([hd])(?:[-_](.+))?$/.exec(bucket);
  if (!m) return bucket;
  const base = m[1] + (m[2] === "h" ? "-hour" : "-day");
  return m[3] ? base + " (" + m[3].charAt(0).toUpperCase() + m[3].slice(1) + ")" : base;
}

// Map the stored pools to core-auth's quota shape [{label, remainingFraction, resetTime}].
function claudeQuota(account) {
  const q = account.cachedQuota;
  if (!q) return undefined;
  const pools = [];
  const add = (pool, label) => {
    if (!pool || typeof pool.utilization !== "number") return;
    pools.push({ label, remainingFraction: Math.max(0, Math.min(1, 1 - pool.utilization)), resetTime: pool.reset });
  };
  if (q.pools) for (const [bucket, pool] of Object.entries(q.pools).sort(([a], [b]) => a.localeCompare(b))) add(pool, poolLabel(bucket));
  else { add(q.fiveHour, "5-hour"); add(q.sevenDay, "7-day"); }   // pre-discovery cached shape
  return pools.length ? pools : undefined;
}

// On-demand refresh: a tiny max_tokens:1 ping whose response headers carry current
// pool state (works even when rate-limited — the 429 still reports it).
async function refreshQuotaOne(manager, accountId) {
  const access = await manager.ensureAccess(accountId);
  if (!access) return;
  const res = await fetch(ANTHROPIC_API_BASE + "/v1/messages", {
    method: "POST",
    headers: { Authorization: "Bearer " + access, "anthropic-version": ANTHROPIC_VERSION, "anthropic-beta": ANTHROPIC_OAUTH_BETA, "Content-Type": "application/json" },
    body: JSON.stringify({ model: "claude-haiku-4-5", max_tokens: 1, system: [{ type: "text", text: CLAUDE_CODE_SYSTEM }], messages: [{ role: "user", content: "ping" }] }),
  });
  captureQuota(manager, accountId, res.headers);
}

async function refreshQuotaAll(manager) {
  for (const account of manager.list()) {
    if (account.enabled === false) continue;
    try { await refreshQuotaOne(manager, account.id); } catch {}
  }
}

function claudeStatus(account, now) {
  if (account.enabled === false) return "disabled";
  if (typeof account.coolingDownUntil === "number" && account.coolingDownUntil > now) return "cooling-down";
  const lanes = account.rateLimitResetTimes || {};
  if (Object.values(lanes).some((reset) => typeof reset === "number" && reset > now)) return "rate-limited";
  return "active";
}

async function verify(manager, view) {
  const name = view.email || view.id;
  try {
    const access = await manager.ensureAccess(view.id);
    if (!access) { out("✗ " + name + ": no access token"); return; }
    const aborter = new AbortController();
    const timer = setTimeout(() => aborter.abort(), 20000);
    let response;
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
      // broken token (wrong scopes) — disable + flag for re-login so it isn't used
      manager.mutate(view.id, (a) => { a.enabled = false; a.disabledReason = "re-login required (token lacks inference scope)"; });
      out("✗ " + name + ": disabled — re-login required (403 scope)");
    }
    else out("✗ " + name + ": " + response.status);
  } catch (error) {
    out("✗ " + name + ": " + ((error && error.message) || error));
  }
}

async function verifyAll(manager) {
  for (const account of manager.list()) {
    if (account.enabled === false) { out("- " + (account.email || account.id) + ": skipped (disabled)"); continue; }
    await verify(manager, { id: account.id, email: account.email });
  }
  out("Done.");
}

async function refreshToken(manager, view) {
  const name = view.email || view.id;
  try {
    out((await manager.refresh(view.id)) ? "✓ refreshed " + name : "✗ no OAuth config / refresh token for " + name);
  } catch (error) {
    out("✗ refresh failed for " + name + ": " + ((error && error.message) || error));
  }
}

export function createClaudeAccounts(manager) {
  return accountControllerFromManager(manager, {
    status: claudeStatus,
    // surface WHY the system disabled an account (e.g. 403 -> "re-login required").
    // Only disabledReason renders: cooldownReason holds transient raw error text
    // (e.g. "TypeError: fetch failed") that must never leak into the account row.
    detail: (account) => (account.enabled === false && account.disabledReason) ? account.disabledReason : undefined,
    quota: claudeQuota,
    refreshQuota: () => refreshQuotaAll(manager),
    login: async () => {
      const account = await login({ log: (message) => process.stderr.write(message + "\n") });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
    actions: () => [{ label: "Verify all accounts", run: () => verifyAll(manager) }],
    accountActions: (view) => [
      { label: "Refresh quota", run: () => refreshQuotaOne(manager, view.id) },
      { label: "Verify access", run: () => verify(manager, view) },
      { label: "Refresh token", run: () => refreshToken(manager, view) },
    ],
  });
}
