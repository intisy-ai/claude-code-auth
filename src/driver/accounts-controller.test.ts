// @ts-nocheck
import { describe, it, expect } from "vitest";
import { accountHasQuota } from "./accounts-controller.js";

describe("accountHasQuota (claude-code)", () => {
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
