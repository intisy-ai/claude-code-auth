// @ts-nocheck
// Claude model catalog exposed to the loaders / opencode.json (core-auth
// ProviderModel shape: { name }). Ids are the Anthropic model ids the
// subscription serves; the OpenCode custom "claude-code" provider and the
// claude-code-loader Providers tab both read these.

// Declaration order = the "recommended" sort, so each thinking model is listed
// directly above its non-thinking base.
export const models = {
  "claude-opus-4-6-thinking": { name: "Claude Opus 4.6 Thinking (Claude Code)" },
  "claude-opus-4-6": { name: "Claude Opus 4.6 (Claude Code)" },
  "claude-sonnet-4-6-thinking": { name: "Claude Sonnet 4.6 Thinking (Claude Code)" },
  "claude-sonnet-4-6": { name: "Claude Sonnet 4.6 (Claude Code)" },
  "claude-haiku-4-5": { name: "Claude Haiku 4.5 (Claude Code)" },
};
