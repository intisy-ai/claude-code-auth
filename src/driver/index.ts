// @ts-nocheck
// The claude-code driver: a thin object on top of core-auth. core-auth owns
// account storage, selection, token refresh, and rate-limit/cooldown state; this
// driver owns only the Anthropic request rewrite (Bearer OAuth + Claude Code
// system block) and rotation across subscription accounts.

import { defineProvider, AccountManager } from "../../core-auth/dist/index.js";
import { ANTHROPIC_API_BASE, ANTHROPIC_VERSION, ANTHROPIC_OAUTH_BETA } from "../constants.js";
import { models } from "./models.js";
import { oauthConfig } from "./config.js";
import { login, loginFlow } from "./login.js";
import { createClaudeAccounts } from "./accounts-controller.js";
import {
  getMaxAttempts,
  getSelection,
  getDefaultCooldownSeconds,
  getMaxCooldownSeconds,
  getSetting,
  setSetting,
} from "./settings.js";

const PROVIDER_ID = "claude-code";

const manager = new AccountManager(PROVIDER_ID, {
  selection: getSelection(),
  oauth: oauthConfig(),
});

// Exported so the Java-orchestrator delegation shell (javaHandle.ts) and the regression
// harness share this ONE AccountManager instance (state consistency).
export { manager };

async function handle(request, ctx) {
  const { handleViaJavaOrchestrator } = await import("./javaHandle.js");
  return handleViaJavaOrchestrator(request, ctx);
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
