// @ts-nocheck
// OpenCode entry. OpenCode invokes every exported FUNCTION as a hook, so only the
// provider plugin is exported as one; the api host reads the non-function default instead.
// Slash-command / config invocations shell back in as `node <bundle> <action>`;
// handle those first and exit so they never register the provider.
import { emitEvent } from "@intisy-ai/basekit";
import { defineProviderPlugin, setActivityEmitter } from "@intisy-ai/basekit/auth";
import { maybeRunCli } from "./commands.js";
import { driver } from "./driver/index.js";

// Best-effort: let basekit/auth's account activity (added/removed/login/rate_limited/models_refreshed) flow onto the bus.
setActivityEmitter((spec: unknown, source: string) => emitEvent(spec, source));

// The readme registration name is the config NAME the driver's settings.ts reads
// (config/claude-code.json), which the manifest states too; the plugin id stays claude-code-auth.
export const ClaudeCodeProvider = await defineProviderPlugin({
  name: "claude-code",
  driver,
  cliGuard: () => maybeRunCli(),
  readme: {
    description:
      "A [basekit/auth](https://github.com/intisy-ai/basekit) provider that signs in to Claude with the real Claude Code OAuth flow and lets you add **multiple Claude subscription accounts**. Both Claude Code (via the loader proxy) and OpenCode route requests through it, rotating accounts and respecting each one's subscription rate limits, so OpenCode uses your Claude Code subscription instead of a pay-per-token API key.",
    architecture: `flowchart TD
    subgraph Driver [claude-code driver, thin layer on basekit auth]
        HANDLE["handle(request), Anthropic request rewrite"]
        LOGIN["loginFlow(), PKCE OAuth"]
    end
    subgraph Core [basekit auth]
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
        "`driver/`, driver + OAuth config/login (request prep now round-trips through basekit/ir, java/claude-provider)",
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
    dependencies: ["basekit", "sync-bridge"],
  },
});

// ClaudeCodeProvider stays exported too: OpenCode invokes every exported function, while an api host reads the default.
export { default } from "./plugin.js";
