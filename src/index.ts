// @ts-nocheck
// OpenCode entry. Export ONLY the provider plugin: OpenCode runs every export as
// a hook, so any extra export would register as a bogus plugin.
// Slash-command / config invocations shell back in as `node <bundle> <action>`;
// handle those first and exit so they never register the provider.
import { deployCommands, defineConfig, defineReadme, maybeRunReadmeCli } from "../core/src/index.js";
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

defineReadme({
  description:
    "A [core-auth](https://github.com/intisy-ai/core-auth) provider that signs in to Claude with the real Claude Code OAuth flow and lets you add **multiple Claude subscription accounts**. Both Claude Code (via the loader proxy) and OpenCode route requests through it, rotating accounts and respecting each one's subscription rate limits — so OpenCode uses your Claude Code subscription instead of a pay-per-token API key.",
  architecture: `flowchart TD
    subgraph Driver [claude-code driver — thin layer on core-auth]
        HANDLE["handle(request) — Anthropic request rewrite"]
        LOGIN["loginFlow() — PKCE OAuth"]
    end
    subgraph Core [core-auth]
        MGR[AccountManager: select / refresh / rotate]
        STORE[(accounts.json)]
        MGR <--> STORE
    end
    CC[Claude Code loader proxy] -->|dist/handler.js handle| HANDLE
    OC[OpenCode loader] -->|loader.fetch| HANDLE
    HANDLE -->|acquire account + token| MGR
    HANDLE -->|"Bearer + anthropic-beta: oauth + Claude Code system block"| API[(api.anthropic.com)]
    API -->|429 / 529| MGR
    LOGIN -->|platform.claude.com OAuth| STORE`,
  structure: {
    src: [
      "`driver/` — driver + OAuth config/login",
      "`oauth/` — PKCE flow",
      "`plugin/request.ts` — Anthropic rewrite",
      "`commands.ts` — slash-commands",
      "`handler.ts`/`index.ts`/`cli.ts` — entries",
    ],
    dist: [
      "`index.js` — OpenCode bundle",
      "`handler.js` — Claude loader bundle",
      "`cli.js` — CLI bundle",
    ],
  },
  commands: CLAUDE_COMMANDS,
  dependencies: ["core", "core-auth", "sync-bridge"],
});

if (maybeRunReadmeCli("claude-code")) process.exit(0);

if (await maybeRunCli("claude-code")) {
  process.exit(0);
}
try {
  deployCommands("claude-code-auth", CLAUDE_COMMANDS);
} catch {
  /* best-effort */
}

export { ClaudeCodeProvider } from "./driver/index.js";
