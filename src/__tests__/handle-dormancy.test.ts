// @ts-nocheck
// T6c2 DORMANCY PROOF — with the flag OFF (default) the delegation shell javaHandle.ts is NEVER
// reached, so neither it nor the ~1MB TeaVM orchestrator ESM it imports is ever loaded/executed:
// zero runtime risk to live `cc`. javaHandle is mocked to a spy here; the guard's dynamic import
// `await import("./javaHandle.js"); return handleViaJavaOrchestrator(...)` runs the spy immediately
// once entered, so "spy NOT called" ⟺ "the ON branch was never entered" ⟺ the delegation (and its
// orchestrator import) never happened. Flag ON drives the spy, proving the guard routes correctly.

import { describe, it, expect, beforeEach, afterAll, vi } from "vitest";

const D = vi.hoisted(() => {
  const state: any = { acquireNull: true, enabledCount: 0, calls: [] };
  class FakeAccountManager {
    constructor() {}
    async acquire() { return state.acquireNull ? null : { account: { id: "x" }, access: "t" }; }
    list() { return Array.from({ length: state.enabledCount }, (_, i) => ({ id: "e" + i, enabled: true })); }
    mutate() {}
    reportError() {}
    reportRateLimit() {}
    reportSuccess() {}
  }
  const proxy = { selectForAccount: () => null, reportResult: () => {}, reportRateLimit: () => {} };
  const delegateSpy = vi.fn(async () => new Response("DELEGATED", { status: 299 }));
  return { state, FakeAccountManager, proxy, delegateSpy };
});

vi.mock("../../core-auth/dist/index.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return { ...actual, AccountManager: D.FakeAccountManager, proxyManager: D.proxy, getAutoCandidates: () => [] };
});

// Replace the whole delegation shell with a spy — so if the OFF path ever imported/invoked it we
// would see the call, and importing this test file never pulls in the real TeaVM orchestrator.
vi.mock("../driver/javaHandle.js", () => ({ handleViaJavaOrchestrator: D.delegateSpy }));

import { driver } from "../driver/index.js";

const req = () => new Request("https://loader.local/v1/messages", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ model: "claude-sonnet-4", messages: [] }) });
const ctx = { model: "", log: () => {} };

beforeEach(() => {
  delete process.env.HUB_CLAUDE_JAVA_HANDLE;
  D.delegateSpy.mockClear();
  D.state.acquireNull = true;
  D.state.enabledCount = 0;
});
afterAll(() => { delete process.env.HUB_CLAUDE_JAVA_HANDLE; });

describe("dormancy: flag OFF never touches the Java delegation shell", () => {
  it("flag OFF (default) takes the pure-TS path and never calls the delegate", async () => {
    const r = await driver.handle(req(), ctx); // no account -> terminal chatError 400 via TS path
    expect(D.delegateSpy).not.toHaveBeenCalled();
    expect(r.status).toBe(400); // proves the real TS branch ran (not the 299 delegate stub)
  });

  it("env HUB_CLAUDE_JAVA_HANDLE=1 routes into the delegation shell", async () => {
    process.env.HUB_CLAUDE_JAVA_HANDLE = "1";
    const r = await driver.handle(req(), ctx);
    expect(D.delegateSpy).toHaveBeenCalledTimes(1);
    expect(r.status).toBe(299); // the spy's response, proving delegation occurred
  });
});
