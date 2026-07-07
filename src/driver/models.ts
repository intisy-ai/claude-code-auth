// @ts-nocheck
// STATIC FALLBACK Claude model catalog (core-auth ProviderModel shape { name }).
// The driver also implements fetchModels() (driver/index.ts) which pulls the live
// list from Anthropic /v1/models on login / "Refresh models"; this constant is the
// displayed default when no account is authed yet or the live fetch fails, so it
// must stay current. Ids are the Anthropic model ids the subscription serves.
// Declaration order = the default manual/catalog order.
export const models = {
  "claude-opus-4-8": { name: "Claude Opus 4.8 (Claude Code)" },
  "claude-sonnet-5": { name: "Claude Sonnet 5 (Claude Code)" },
  "claude-haiku-4-5": { name: "Claude Haiku 4.5 (Claude Code)" },
};
