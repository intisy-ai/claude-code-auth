// @ts-nocheck
// User-facing driver settings, backed by core's per-plugin config store
// (config/claude-code.json), read/written via core getConfigValue/setConfigValue.
// Distinct from config.ts, which holds the OAuth client config.

import { getConfigValue, setConfigValue } from "../../core/src/index.js";

const PACKAGE_NAME = "claude-code";
const DEFAULT_MAX_ATTEMPTS = 4;
const DEFAULT_SELECTION = "hybrid";
const SELECTION_STRATEGIES = ["sticky", "round-robin", "hybrid"];
const DEFAULT_COOLDOWN_SECONDS = 60;
const MAX_COOLDOWN_SECONDS = 900;

export function getSetting(key, fallback) {
  const value = getConfigValue(PACKAGE_NAME, key);
  return value === undefined ? fallback : value;
}

export function setSetting(key, value) {
  setConfigValue(PACKAGE_NAME, key, value);
}

// Typed getter for the one wired setting: how many accounts to try per request.
export function getMaxAttempts(): number {
  const value = Number(getSetting("max_account_attempts", DEFAULT_MAX_ATTEMPTS));
  return Number.isFinite(value) && value >= 1 ? Math.floor(value) : DEFAULT_MAX_ATTEMPTS;
}

// Account selection strategy passed to core-auth's AccountManager at construction.
export function getSelection(): string {
  const value = getSetting("account_selection_strategy", DEFAULT_SELECTION);
  return SELECTION_STRATEGIES.includes(value) ? value : DEFAULT_SELECTION;
}

// Base cooldown (seconds) for a 429/529 without a retry-after header; doubles per attempt.
export function getDefaultCooldownSeconds(): number {
  const value = Number(getSetting("default_cooldown_seconds", DEFAULT_COOLDOWN_SECONDS));
  return Number.isFinite(value) && value >= 1 ? Math.floor(value) : DEFAULT_COOLDOWN_SECONDS;
}

// Maximum cooldown (seconds) the exponential backoff can grow to.
export function getMaxCooldownSeconds(): number {
  const value = Number(getSetting("max_cooldown_seconds", MAX_COOLDOWN_SECONDS));
  return Number.isFinite(value) && value >= 1 ? Math.floor(value) : MAX_COOLDOWN_SECONDS;
}
