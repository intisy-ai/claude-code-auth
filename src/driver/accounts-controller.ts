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
// bucket (5h, 7d, and any Anthropic adds later, e.g. a per-model weekly bucket
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

// Persist the pools captured from a response's headers onto the account. MERGES
// into the existing pools: headers only carry the buckets relevant to that request
// (a per-model weekly bucket appears only on requests to that model), so replacing
// wholesale would keep dropping pools the usage endpoint discovered.
export function captureQuota(manager, accountId, headers) {
  try {
    const pools = readPools(headers);
    if (!Object.keys(pools).length) return;
    manager.mutate(accountId, (a) => {
      const prev = (a.cachedQuota && a.cachedQuota.pools) || {};
      a.cachedQuota = { pools: { ...prev, ...pools }, at: Date.now() };
    });
  } catch {}
}

// Canonical bucket key for one entry of the usage endpoint's limits[] array, kept
// aligned with the header bucket names so both sources describe the same pools:
// session -> 5h, weekly_all -> 7d, weekly_scoped(Fable) -> 7d-fable, else generic.
function bucketOfLimit(limit) {
  if (!limit || typeof limit !== "object") return null;
  const scope = limit.scope && limit.scope.model && (limit.scope.model.display_name || limit.scope.model.id);
  const scopeKey = scope ? String(scope).toLowerCase().replace(/\s+/g, "-") : "";
  if (limit.kind === "session") return "5h";
  if (limit.kind === "weekly_all") return "7d";
  if (limit.group === "weekly" && scopeKey) return "7d-" + scopeKey;
  const base = String(limit.kind || limit.group || "");
  return base ? base + (scopeKey ? "-" + scopeKey : "") : null;
}

// Authoritative pool list from the OAuth usage endpoint, the same source Claude
// Code's /usage screen reads. Unlike response headers it returns EVERY pool,
// including per-model weekly buckets (e.g. Fable), without needing a request to
// that model. Returns null on any failure (caller falls back to the header ping).
async function fetchUsagePools(access) {
  const res = await fetch(ANTHROPIC_API_BASE + "/api/oauth/usage", {
    headers: { Authorization: "Bearer " + access, "anthropic-beta": ANTHROPIC_OAUTH_BETA, "anthropic-version": ANTHROPIC_VERSION },
  });
  if (!res.ok) return null;
  const data = await res.json();
  if (!data || !Array.isArray(data.limits)) return null;
  const pools = {};
  for (const limit of data.limits) {
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

// On-demand refresh: the usage endpoint first (full, authoritative pool list,
// REPLACES the cache); fall back to a tiny max_tokens:1 ping whose response
// headers carry the request-relevant pools (merged into the cache).
async function refreshQuotaOne(manager, accountId) {
  const access = await manager.ensureAccess(accountId);
  if (!access) return;
  let pools = null;
  try { pools = await fetchUsagePools(access); } catch {}
  if (pools) {
    manager.mutate(accountId, (a) => { a.cachedQuota = { pools, at: Date.now() }; });
    return;
  }
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
      // broken token (wrong scopes): disable + flag for re-login so it isn't used
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

// Quota still remaining? (any unified pool below 100% utilization). Unknown -> false.
export function accountHasQuota(account) {
  const q = account && account.cachedQuota;
  const pools = q && q.pools;
  if (!pools) return false;
  return Object.values(pools).some((p) => p && typeof p.utilization === "number" && p.utilization < 1);
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
    refreshQuotaOne: (id) => refreshQuotaOne(manager, id),
    login: async () => {
      const account = await login({ log: (message) => process.stderr.write(message + "\n") });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
    actions: () => [{ label: "Verify all accounts", run: () => verifyAll(manager) }],
    accountActions: (view) => [
      { label: "Verify access", run: () => verify(manager, view) },
      { label: "Refresh token", run: () => refreshToken(manager, view) },
    ],
  });
}
