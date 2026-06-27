// @ts-nocheck
// OpenCode entry. Export ONLY the provider plugin: OpenCode runs every export as
// a hook, so any extra export would register as a bogus plugin.
// Slash-command / config invocations shell back in as `node <bundle> <action>`;
// handle those first and exit so they never register the provider.
import { deployCommands, ensureConfig } from "../core/src/index.js";
import { CLAUDE_COMMANDS, maybeRunCli } from "./commands.js";

if (await maybeRunCli("claude-code-auth")) {
  process.exit(0);
}
try {
  deployCommands("claude-code-auth", CLAUDE_COMMANDS);
  ensureConfig("claude-code-auth", { logging: true });
} catch {
  /* best-effort */
}

export { ClaudeCodeProvider } from "./driver/index.js";
