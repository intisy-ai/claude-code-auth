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
// Anthropic returns unified rate-limit headers on EVERY response: a 5-hour and a
// 7-day pool, each with utilization (0..>1), reset (epoch s) and status. We capture
// them per account so the Quota view shows real usage without a dedicated quota API.
function readPool(headers, prefix) {
  const util = parseFloat(headers.get("anthropic-ratelimit-unified-" + prefix + "-utilization"));
  const reset = parseInt(headers.get("anthropic-ratelimit-unified-" + prefix + "-reset"), 10);
  const status = headers.get("anthropic-ratelimit-unified-" + prefix + "-status") || undefined;
  if (Number.isNaN(util) && Number.isNaN(reset)) return null;
  return { utilization: Number.isNaN(util) ? null : util, reset: Number.isNaN(reset) ? null : reset * 1000, status };
}

// Persist the pools captured from a response's headers onto the account.
export function captureQuota(manager, accountId, headers) {
  try {
    const fiveHour = readPool(headers, "5h");
    const sevenDay = readPool(headers, "7d");
    if (!fiveHour && !sevenDay) return;
    manager.mutate(accountId, (a) => { a.cachedQuota = { fiveHour, sevenDay, at: Date.now() }; });
  } catch {}
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
  add(q.fiveHour, "5-hour");
  add(q.sevenDay, "7-day");
  return pools.length ? pools : undefined;
}

// On-demand refresh: a tiny max_tokens:1 ping per account whose response headers
// carry current pool state (works even when rate-limited — the 429 still reports it).
async function refreshQuotaAll(manager) {
  for (const account of manager.list()) {
    if (account.enabled === false) continue;
    try {
      const access = await manager.ensureAccess(account.id);
      if (!access) continue;
      const res = await fetch(ANTHROPIC_API_BASE + "/v1/messages", {
        method: "POST",
        headers: { Authorization: "Bearer " + access, "anthropic-version": ANTHROPIC_VERSION, "anthropic-beta": ANTHROPIC_OAUTH_BETA, "Content-Type": "application/json" },
        body: JSON.stringify({ model: "claude-haiku-4-5", max_tokens: 1, system: [{ type: "text", text: CLAUDE_CODE_SYSTEM }], messages: [{ role: "user", content: "ping" }] }),
      });
      captureQuota(manager, account.id, res.headers);
    } catch {}
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
    else if (response.status === 403) out("✗ " + name + ": forbidden (403)");
    else out("✗ " + name + ": " + response.status);
  } catch (error) {
    out("✗ " + name + ": " + ((error && error.message) || error));
  }
}

async function verifyAll(manager) {
  for (const account of manager.list()) await verify(manager, { id: account.id, email: account.email });
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
    quota: claudeQuota,
    refreshQuota: () => refreshQuotaAll(manager),
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
