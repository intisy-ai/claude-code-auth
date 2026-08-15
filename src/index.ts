// @ts-nocheck
// OpenCode entry. OpenCode invokes every exported FUNCTION as a hook, so only the
// provider plugin is exported as one; the api host reads the non-function default instead.
// Slash-command / config invocations shell back in as `node <bundle> <action>`;
// handle those first and exit so they never register the provider.
import { deployCommands, defineConfig, defineCapabilities, defineReadme, maybeRunReadmeCli, emitEvent } from "@intisy-ai/core";
import {
  COMMON_PROVIDER_CAPABILITIES,
  toCapabilitiesFields,
  retryBackoffCapabilities,
  defineProviderPlugin,
  setActivityEmitter,
} from "@intisy-ai/core-auth";
import { CLAUDE_COMMANDS, maybeRunCli } from "./commands.js";
import { driver, CLAUDE_SETTINGS_SCHEMA, RETRY_KEYS } from "./driver/index.js";

// Best-effort: let core-auth's account activity (added/removed/login/rate_limited/models_refreshed) flow onto the bus.
setActivityEmitter((spec: unknown, source: string) => emitEvent(spec, source));

// Registered under the SAME name the driver's settings.ts reads (config/claude-code.json).
// (The deployed bundle/command name stays "claude-code-auth"; only the config NAME is claude-code.)
export const ClaudeCodeProvider = await defineProviderPlugin({
  name: "claude-code",
  packageName: "claude-code-auth",
  driver,
  core: { defineConfig, defineCapabilities, defineReadme, maybeRunReadmeCli, deployCommands },
  configCliGuard: () => maybeRunCli("claude-code"),
  defaults: {
    logging: true,
    max_account_attempts: 4,
    account_selection_strategy: "hybrid",
    default_cooldown_seconds: 60,
    max_cooldown_seconds: 900,
  },
  capabilities: {
    fields: [
      ...COMMON_PROVIDER_CAPABILITIES,
      { key: "logging", type: "boolean", label: "Logging", description: "Write this plugin's log file.", group: "General" },
      ...toCapabilitiesFields(CLAUDE_SETTINGS_SCHEMA),
      ...retryBackoffCapabilities(RETRY_KEYS),
    ],
  },
  readme: {
    description:
      "A [core-auth](https://github.com/intisy-ai/core-auth) provider that signs in to Claude with the real Claude Code OAuth flow and lets you add **multiple Claude subscription accounts**. Both Claude Code (via the loader proxy) and OpenCode route requests through it, rotating accounts and respecting each one's subscription rate limits, so OpenCode uses your Claude Code subscription instead of a pay-per-token API key.",
    architecture: `flowchart TD
    subgraph Driver [claude-code driver, thin layer on core-auth]
        HANDLE["handle(request), Anthropic request rewrite"]
        LOGIN["loginFlow(), PKCE OAuth"]
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
        "`driver/`, driver + OAuth config/login (request prep now round-trips through core-ir, java/claude-provider)",
        "`oauth/`, PKCE flow",
        "`commands.ts`, slash-commands",
        "`handler.ts`/`index.ts`/`cli.ts`, entries",
      ],
      dist: [
        "`index.js`, OpenCode bundle",
        "`handler.js`, Claude loader bundle",
        "`cli.js`, CLI bundle",
      ],
    },
    commands: CLAUDE_COMMANDS,
    dependencies: ["core", "core-auth", "sync-bridge"],
  },
  commands: CLAUDE_COMMANDS,
});

// ClaudeCodeProvider stays exported too: OpenCode invokes every exported function, while an api host reads the default.
export { default } from "./plugin.js";
