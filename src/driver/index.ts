// @ts-nocheck
// The claude-code driver: a thin object on top of core-auth. core-auth owns
// account storage, selection, token refresh, and rate-limit/cooldown state; this
// driver owns only the Anthropic request rewrite (Bearer OAuth + Claude Code
// system block) and rotation across subscription accounts.

import { defineProvider, AccountManager, proxyManager, getAutoCandidates } from "../../core-auth/dist/index.js";
import { prepareClaudeRequest, parseResetMs } from "../plugin/request.js";
import { ANTHROPIC_API_BASE, ANTHROPIC_VERSION, ANTHROPIC_OAUTH_BETA } from "../constants.js";
import { models } from "./models.js";
import { oauthConfig } from "./config.js";
import { login, loginFlow } from "./login.js";
import { createClaudeAccounts, captureQuota } from "./accounts-controller.js";
import {
  getMaxAttempts,
  getSelection,
  getDefaultCooldownSeconds,
  getMaxCooldownSeconds,
  getSetting,
  setSetting,
} from "./settings.js";

const PROVIDER_ID = "claude-code";
const LANE = "messages"; // Claude subscription limits are account-wide

const manager = new AccountManager(PROVIDER_ID, {
  selection: getSelection(),
  oauth: oauthConfig(),
});

function isRateLimitStatus(status) {
  return status === 429 || status === 529;
}

function errorResponse(status, message) {
  return new Response(JSON.stringify({ error: { message } }), {
    status,
    headers: { "content-type": "application/json" },
  });
}

// Resolve the generic "Auto" model: if the request targets claude-code-auto, rewrite
// the body's model to the TOP of the live Auto ranking (leaderboard #1, excluded
// filtered). Resolved per-request so it always tracks the current ranking.
function resolveAutoModel(bodyText, ctx) {
  let obj;
  try { obj = bodyText ? JSON.parse(bodyText) : null; } catch { return bodyText; }
  const requested = String((obj && obj.model) || (ctx && ctx.model) || "");
  const isAuto = requested === "claude-code-auto" || requested.replace(/^claude-code-/, "") === "auto";
  if (!isAuto || !obj) return bodyText;
  const top = getAutoCandidates("claude-code")[0];
  if (!top) return bodyText;          // no ranking yet — leave as-is
  obj.model = top;
  return JSON.stringify(obj);
}

async function handle(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});

  const url = request.url;
  let bodyText;
  try { bodyText = await request.clone().text(); } catch { bodyText = undefined; }
  bodyText = resolveAutoModel(bodyText, ctx);
  const init = { method: request.method, headers: Object.fromEntries(request.headers), body: bodyText };

  const maxAttempts = getMaxAttempts(); // read per-request so config edits apply without a restart
  let lastResponse = null;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const acquired = await manager.acquire(LANE);
    if (!acquired || !acquired.account) return errorResponse(503, "No available Claude account. Run `claude-code-auth login`.");
    const account = acquired.account;
    const access = acquired.access;
    if (!access) { manager.reportError(account.id, attempt, "missing access token"); continue; }

    const proxyUrl = proxyManager.selectForAccount(account.id);

    let prepared;
    try { prepared = prepareClaudeRequest(url, init, access); }
    catch (error) { log("prepare failed: " + error); manager.reportError(account.id, attempt, String(error)); continue; }
    if (proxyUrl) prepared.init.proxy = proxyUrl; // Bun fetch honors .proxy

    let response;
    const started = Date.now();
    let proxyOk = false;
    try { response = await fetch(prepared.request, prepared.init); proxyOk = !!proxyUrl; }
    catch (error) {
      if (proxyUrl) {
        proxyManager.reportResult(proxyUrl, false);
        // proxy unreachable -> retry directly (a dead proxy gives no isolation anyway)
        log("fetch via proxy " + proxyUrl + " failed: " + error + " — retrying directly");
        try {
          const directInit = { ...prepared.init };
          delete directInit.proxy;
          response = await fetch(prepared.request, directInit);
        } catch (directError) {
          log("direct retry failed: " + directError);
          manager.reportError(account.id, attempt, String(directError));
          continue;
        }
      } else {
        log("fetch failed: " + error);
        manager.reportError(account.id, attempt, String(error));
        continue;
      }
    }
    if (proxyOk) proxyManager.reportResult(proxyUrl, true, Date.now() - started);

    // Capture the subscription rate-limit pools (5h + 7d) from this response so the
    // Quota view shows real usage — every response carries the unified-* headers.
    captureQuota(manager, account.id, response.headers);

    if (isRateLimitStatus(response.status)) {
      lastResponse = response;
      manager.reportRateLimit(account.id, LANE, parseResetMs(response, attempt));
      if (proxyUrl) proxyManager.reportRateLimit(proxyUrl);
      continue; // rotate account
    }

    if (response.status === 401) {
      lastResponse = response;
      manager.reportError(account.id, attempt, "401 unauthorized");
      continue; // token may be revoked; try another account
    }

    if (response.ok) {
      manager.reportSuccess(account.id);
      return response; // already Anthropic format (incl. SSE) — pass through
    }

    return response; // non-retryable upstream error -> surface as-is
  }

  // All accounts exhausted — return the REAL upstream 429 (with Anthropic's
  // anthropic-ratelimit-unified-* headers) so Claude Code renders its native
  // rate-limit UI ("session limit — resets X"). The loader proxy detects the 429 for
  // fallback and normalizes non-claude providers into this same native shape.
  return lastResponse || errorResponse(502, "Claude request failed after " + maxAttempts + " attempts");
}

// Live model catalog: pull the account's available models from Anthropic /v1/models
// so "Refresh models" (and login) actually update the list instead of re-reading the
// static fallback. core-auth's resolveProviderModels calls this when an account exists
// and falls back to the static `models` above on null/failure. Uses ensureAccess (no
// rotation side effects) rather than acquire().
async function fetchModels(ctx) {
  const log = (ctx && ctx.log) || (() => {});
  let access;
  try {
    const accounts = (manager.load().accounts || []).filter((a) => a && a.enabled !== false);
    if (!accounts.length) return null; // no authed account -> static fallback
    access = await manager.ensureAccess(accounts[0].id);
  } catch (error) { log("fetchModels: could not get access token: " + error); return null; }
  if (!access) return null;
  try {
    const res = await fetch(ANTHROPIC_API_BASE + "/v1/models?limit=1000", {
      headers: {
        authorization: "Bearer " + access,
        "anthropic-version": ANTHROPIC_VERSION,
        "anthropic-beta": ANTHROPIC_OAUTH_BETA,
      },
    });
    if (!res.ok) { log("fetchModels: /v1/models returned " + res.status); return null; }
    const data = await res.json();
    const list = (data && data.data) || [];
    const out = {};
    for (const model of list) {
      if (!model || !model.id || !String(model.id).startsWith("claude-")) continue;
      out[model.id] = { name: (model.display_name || model.id) + " (Claude Code)" };
    }
    if (!Object.keys(out).length) return null;
    return { models: out };
  } catch (error) { log("fetchModels failed: " + error); return null; }
}

export const driver = {
  id: PROVIDER_ID,
  label: "Claude Code",
  opencodeProvider: "claude-code", // own namespace so OpenCode routes through our loader
  opencodeNpm: "@ai-sdk/anthropic",
  models,
  fetchModels,
  sorts: ["leaderboard"],   // opt into core's built-in quality sort (manual is automatic)
  handle,
  login,
  loginFlow,
  accounts: createClaudeAccounts(manager),
  proxies: true,
  settings: {
    groups: [
      {
        title: "Account rotation",
        fields: [
          {
            key: "max_account_attempts",
            label: "Max account attempts",
            type: "number",
            min: 1,
            max: 20,
            hint: "How many accounts to try before giving up on a request.",
          },
          {
            key: "account_selection_strategy",
            label: "Account selection strategy",
            type: "enum",
            options: ["sticky", "round-robin", "hybrid"],
            hint: "How accounts are picked across requests (applies on restart).",
          },
        ],
      },
      {
        title: "Rate limits",
        fields: [
          {
            key: "default_cooldown_seconds",
            label: "Default cooldown (seconds)",
            type: "number",
            min: 1,
            max: 3600,
            hint: "Base cooldown (seconds) for a 429 with no retry-after; doubles per attempt.",
          },
          {
            key: "max_cooldown_seconds",
            label: "Max cooldown (seconds)",
            type: "number",
            min: 1,
            max: 3600,
            hint: "Maximum cooldown (seconds) the backoff can grow to.",
          },
        ],
      },
    ],
    get: (key) => {
      if (key === "max_account_attempts") return getMaxAttempts();
      if (key === "account_selection_strategy") return getSelection();
      if (key === "default_cooldown_seconds") return getDefaultCooldownSeconds();
      if (key === "max_cooldown_seconds") return getMaxCooldownSeconds();
      return getSetting(key, undefined);
    },
    set: (key, value) => setSetting(key, value),
  },
};

export const ClaudeCodeProvider = defineProvider(driver).opencode;
