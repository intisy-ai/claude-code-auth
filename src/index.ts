// @ts-nocheck
// OpenCode entry. Export ONLY the provider plugin: OpenCode runs every export as
// a hook, so any extra export would register as a bogus plugin.
// Slash-command / config invocations shell back in as `node <bundle> <action>`;
// handle those first and exit so they never register the provider.
import { deployCommands, defineConfig } from "../core/src/index.js";
import { CLAUDE_COMMANDS, maybeRunCli } from "./commands.js";

// Register config under the SAME name the driver's settings.ts reads (config/claude-code.json)
// and with the FULL set of real settings, so `config schema`/`/config`/the loader editor
// expose them all (not just logging) and edits round-trip to what the driver reads. (The
// deployed bundle/command name stays "claude-code-auth"; only the config NAME is claude-code.)
defineConfig("claude-code", {
  logging: true,
  max_account_attempts: 4,
  account_selection_strategy: "hybrid",
  default_cooldown_seconds: 60,
  max_cooldown_seconds: 900,
});

if (await maybeRunCli("claude-code")) {
  process.exit(0);
}
try {
  deployCommands("claude-code-auth", CLAUDE_COMMANDS);
} catch {
  /* best-effort */
}

export { ClaudeCodeProvider } from "./driver/index.js";
