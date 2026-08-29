// claude-code-auth carries no bundled front-door: basekit/auth's createProviderPlugin resolves the
// app-shaped hooks from whatever AppFrontDoor the app layer (a loader) published at runtime via
// HUB_APP_FRONTDOOR. With no front-door published and no out-of-process proxy configured, the
// plugin is inert (no app-shaped hooks to build).
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

let dir: string;
let saved: Record<string, string | undefined>;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "claude-code-auth-offline-frontdoor-"));
  mkdirSync(join(dir, "config"), { recursive: true });
  saved = {
    HUB_CONFIG_DIR: process.env.HUB_CONFIG_DIR,
    CORE_APP: process.env.CORE_APP,
    OPENCODE_CONFIG: process.env.OPENCODE_CONFIG,
    HUB_OC_PROXY: process.env.HUB_OC_PROXY,
    HUB_APP_FRONTDOOR: process.env.HUB_APP_FRONTDOOR,
  };
  process.env.HUB_CONFIG_DIR = dir;
  process.env.CORE_APP = "opencode";
  // Isolate the opencode.json merge target so the test never touches a real home.
  process.env.OPENCODE_CONFIG = join(dir, "opencode.json");
  delete process.env.HUB_OC_PROXY;
  delete process.env.HUB_APP_FRONTDOOR;
});

afterEach(() => {
  for (const [key, value] of Object.entries(saved)) {
    if (value === undefined) delete (process.env as Record<string, string | undefined>)[key];
    else process.env[key] = value;
  }
  rmSync(dir, { recursive: true, force: true });
});

describe("claude-code-auth with no injected front-door", () => {
  it("returns an inert plugin (no app-shaped hooks) instead of bundling its own front-door", async () => {
    // @ts-ignore build artifact (produced by `npm run build`)
    const { ClaudeCodeProvider } = await import("../../dist/index.js");
    const hooks = await ClaudeCodeProvider({});
    expect(hooks.auth).toBeUndefined();
  });
});
