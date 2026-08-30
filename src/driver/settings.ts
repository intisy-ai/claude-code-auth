// User-facing driver settings, backed by core's per-plugin config store
// (config/claude-code.json), read/written via core getConfigValue/setConfigValue.
// Distinct from config.ts, which holds the OAuth client config.

import { getConfigValue, setConfigValue } from "@intisy-ai/basekit";
import { coercePositiveInt, type AccountSelectionStrategy } from "@intisy-ai/basekit/auth";

const PACKAGE_NAME = "claude-code";
const DEFAULT_MAX_ATTEMPTS = 4;
const DEFAULT_SELECTION: AccountSelectionStrategy = "hybrid";
const SELECTION_STRATEGIES: readonly AccountSelectionStrategy[] = ["sticky", "round-robin", "hybrid"];
const DEFAULT_COOLDOWN_SECONDS = 60;
const MAX_COOLDOWN_SECONDS = 900;

/**
 * One setting's configured value.
 *
 * @param key - the setting to read
 * @param fallback - what to answer when this home has configured none
 * @returns the configured value, or the fallback
 */
export function getSetting(key: string, fallback?: unknown): unknown {
  const value = getConfigValue(PACKAGE_NAME, key);
  return value === undefined ? fallback : value;
}

/**
 * Writes one setting.
 *
 * @param key - the setting to write
 * @param value - what to write
 */
export function setSetting(key: string, value: unknown): void {
  setConfigValue(PACKAGE_NAME, key, value);
}

// Typed getter for the one wired setting: how many accounts to try per request.
/**
 * How many accounts to try per request.
 *
 * @returns the configured attempt limit
 */
export function getMaxAttempts(): number {
  return coercePositiveInt(getSetting("max_account_attempts", DEFAULT_MAX_ATTEMPTS), DEFAULT_MAX_ATTEMPTS);
}

// Account selection strategy passed to basekit/auth's AccountManager at construction.
/**
 * How accounts are picked across requests.
 *
 * @returns the configured strategy, or the default when the configured one is unknown
 */
export function getSelection(): AccountSelectionStrategy {
  const value = getSetting("account_selection_strategy", DEFAULT_SELECTION);
  return SELECTION_STRATEGIES.includes(value as AccountSelectionStrategy)
    ? (value as AccountSelectionStrategy)
    : DEFAULT_SELECTION;
}

// Base cooldown (seconds) for a 429/529 without a retry-after header; doubles per attempt.
/**
 * How long to cool an account down for on a rate limit the upstream gave no reset time for.
 *
 * @returns the configured base cooldown, in seconds
 */
export function getDefaultCooldownSeconds(): number {
  return coercePositiveInt(getSetting("default_cooldown_seconds", DEFAULT_COOLDOWN_SECONDS), DEFAULT_COOLDOWN_SECONDS);
}

// Maximum cooldown (seconds) the exponential backoff can grow to.
/**
 * The ceiling the doubling cooldown stops at.
 *
 * @returns the configured maximum cooldown, in seconds
 */
export function getMaxCooldownSeconds(): number {
  return coercePositiveInt(getSetting("max_cooldown_seconds", MAX_COOLDOWN_SECONDS), MAX_COOLDOWN_SECONDS);
}
