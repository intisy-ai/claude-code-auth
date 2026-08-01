// @ts-nocheck
import { describe, it, expect, beforeAll } from "vitest";
import { accountHasQuota } from "./accounts-controller.js";
import { initCoreAuth } from "../../core-auth/dist/index.js";

describe("accountHasQuota (claude-code)", () => {
  // accountHasQuota now delegates to core-auth's quota-health.ts (getCoreAuth().quotaHasCapacity),
  // which throws unless core-auth has been initialized. This test calls accountHasQuota directly,
  // not through AccountManager.acquire/ensureAccess (which self-init), so it needs its own init.
  beforeAll(async () => {
    await initCoreAuth();
  });

  it("true when a pool is below 100% utilization", () => {
    expect(accountHasQuota({ cachedQuota: { pools: { "5h": { utilization: 0.5 }, "7d": { utilization: 1 } } } })).toBe(true);
  });
  it("false when all pools maxed", () => {
    expect(accountHasQuota({ cachedQuota: { pools: { "5h": { utilization: 1 } } } })).toBe(false);
  });
  it("false when unknown", () => {
    expect(accountHasQuota({})).toBe(false);
  });
});
