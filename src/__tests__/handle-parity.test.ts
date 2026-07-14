// @ts-nocheck
// T6c2 PARITY HARNESS — runs BOTH the live pure-TS `handle` (driver.handle, flag OFF) and the
// Java-orchestrator delegation (handleViaJavaOrchestrator) against the SAME scripted scenarios,
// asserting IDENTICAL outcomes (final Response status + headers + body) AND the IDENTICAL ordered
// sequence of manager.* / proxyManager.* calls (incl. the reportRateLimit ipSuspected proxy
// re-fire). This offline diff is the evidence that flipping the flag in T6d is safe.
//
// Fakes: core-auth's AccountManager + proxyManager + getAutoCandidates are replaced (so both paths
// share ONE instrumented manager/proxy); the real chatError / accountControllerFromManager /
// defineProvider stay; captureQuota / accountHasQuota (accounts-controller) stay real; global fetch
// is scripted per scenario. Date.now is frozen so proxy-latency / captureQuota `at` are deterministic.
//
// KNOWN, INTENTIONAL DIVERGENCE (documented, NOT a defect): on a transport failure the TS path
// reports the real JS Error text to manager.reportError, while the Java path reports the generic
// "transport failed" token — because the live Error object never crosses into Java by design
// (ClaudeHandleOrchestrator javadoc L247-253; T6b). The ROTATION effect (a reportError for that
// account+attempt) is identical; only the free-text message differs, so the harness normalizes just
// that one field (see normalizeCalls) and this comment records the raw values.

import { describe, it, expect, beforeEach, afterAll, vi } from "vitest";

const H = vi.hoisted(() => {
  const harness: any = {
    accounts: [],
    acquireScript: [],
    acquireIdx: 0,
    fetchScript: [],
    fetchIdx: 0,
    proxyScript: {},
    autoCandidates: [],
    calls: [],
    now: 1_700_000_000_000,
  };

  const record = (entry: any[]) => harness.calls.push(entry);
  const snap = (a: any) => a && JSON.parse(JSON.stringify({
    id: a.id, enabled: a.enabled, disabledReason: a.disabledReason,
    cachedQuota: a.cachedQuota, rateLimitResetTimes: a.rateLimitResetTimes,
  }));
  const find = (id: string) => harness.accounts.find((x: any) => x.id === id);

  class FakeAccountManager {
    constructor() {}
    async acquire(lane: string) {
      const next = harness.acquireScript[harness.acquireIdx++] ?? null;
      record(["manager.acquire", lane, next && next.account ? next.account.id : null]);
      return next;
    }
    list() { return harness.accounts; }
    mutate(id: string, fn: (a: any) => void) {
      const a = find(id);
      if (a) fn(a);
      record(["manager.mutate", id, snap(a)]);
    }
    reportError(id: string, attempt: number, reason: string) {
      const a = find(id);
      if (a) { a.coolingDownUntil = harness.now + 1000; a.cooldownReason = reason; }
      record(["manager.reportError", id, attempt, reason]);
    }
    reportRateLimit(id: string, lane: string, resetMs: any) {
      const a = find(id);
      if (a) { a.rateLimitResetTimes = a.rateLimitResetTimes || {}; a.rateLimitResetTimes[lane] = resetMs; }
      record(["manager.reportRateLimit", id, lane, resetMs]);
    }
    reportSuccess(id: string) {
      const a = find(id);
      if (a) { a.coolingDownUntil = 0; a.cooldownReason = null; }
      record(["manager.reportSuccess", id]);
    }
  }

  const fakeProxyManager = {
    selectForAccount(accountId: string, providerId: string) {
      const url = harness.proxyScript[accountId] ?? null;
      record(["proxy.selectForAccount", accountId, providerId, url]);
      return url;
    },
    // ms (latency) is dropped from the record: it is Date.now()-started, non-deterministic, and
    // not a correctness signal; ok already distinguishes success from failure.
    reportResult(url: string, ok: boolean) { record(["proxy.reportResult", url, ok]); },
    reportRateLimit(url: string, opts: any) { record(["proxy.reportRateLimit", url, opts]); },
  };

  return { harness, FakeAccountManager, fakeProxyManager };
});

vi.mock("../../core-auth/dist/index.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    AccountManager: H.FakeAccountManager,
    proxyManager: H.fakeProxyManager,
    getAutoCandidates: () => H.harness.autoCandidates,
  };
});

import { driver, manager } from "../driver/index.js";
import { handleViaJavaOrchestrator } from "../driver/javaHandle.js";
import { getMaxAttempts } from "../driver/settings.js";

const harness = H.harness;

// --- helpers ----------------------------------------------------------------

const jsonHeaders = { "content-type": "application/json" };

function resp(status: number, headers: Record<string, string>, body: string) {
  return () => new Response(body, { status, headers });
}
function transportError() {
  return () => { throw new Error("__TRANSPORT__"); };
}

function resetForRun(sc: any) {
  harness.accounts = JSON.parse(JSON.stringify(sc.accounts || []));
  harness.acquireScript = (sc.acquire || []).map((e: any) =>
    e === null ? null : { account: harness.accounts.find((a: any) => a.id === e.id) || { id: e.id }, access: e.access },
  );
  harness.acquireIdx = 0;
  harness.fetchScript = (sc.fetch || []).slice();
  harness.fetchIdx = 0;
  harness.proxyScript = { ...(sc.proxy || {}) };
  harness.autoCandidates = sc.autoCandidates || [];
  harness.calls = [];
}

// Normalize the ONE documented divergence: the transport-failure reportError message.
function normalizeCalls(calls: any[]) {
  return calls.map((c) => {
    if (c[0] === "manager.reportError") {
      const reason = String(c[3] ?? "");
      if (reason === "transport failed" || reason.includes("__TRANSPORT__")) {
        return [c[0], c[1], c[2], "<transport-failed>"];
      }
    }
    return c;
  });
}

async function snapshotResponse(r: Response) {
  return {
    status: r.status,
    headers: Object.fromEntries([...r.headers.entries()].sort()),
    body: await r.text(),
  };
}

async function runBothPaths(sc: any) {
  const makeReq = () =>
    new Request("https://loader.local/v1/messages", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }),
    });
  const ctx = { model: "", log: () => {} };

  resetForRun(sc);
  const tsResp = await driver.handle(makeReq(), ctx);
  const tsSnap = await snapshotResponse(tsResp);
  const tsCalls = normalizeCalls(harness.calls.slice());

  resetForRun(sc);
  const jvResp = await handleViaJavaOrchestrator(makeReq(), ctx);
  const jvSnap = await snapshotResponse(jvResp);
  const jvCalls = normalizeCalls(harness.calls.slice());

  return { tsSnap, jvSnap, tsCalls, jvCalls };
}

// --- fixtures ---------------------------------------------------------------

const POOL_HEADERS = {
  "anthropic-ratelimit-unified-5h-utilization": "0.5",
  "anthropic-ratelimit-unified-5h-status": "allowed",
};
const RESET_HEADERS = { "anthropic-ratelimit-unified-reset": "1700000100" };

let realFetch: any;
beforeEach(() => {
  delete process.env.HUB_CLAUDE_JAVA_HANDLE;
  vi.spyOn(Date, "now").mockReturnValue(harness.now);
  realFetch = globalThis.fetch;
  // Scripted fetch — consumes harness.fetchScript by call order; NO real network is ever touched.
  globalThis.fetch = (async () => {
    const thunk = harness.fetchScript[harness.fetchIdx++];
    if (!thunk) throw new Error("parity harness: fetch script exhausted");
    return thunk();
  }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; vi.restoreAllMocks(); });

// --- scenarios --------------------------------------------------------------

const N = getMaxAttempts(); // whatever the effective config yields; both paths read the same value

const scenarios: any[] = [
  {
    name: "happy path — 200 on attempt 0 (captureQuota mutate + reportSuccess + SERVE)",
    accounts: [{ id: "acc1", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }],
    fetch: [resp(200, { ...jsonHeaders, ...POOL_HEADERS }, '{"ok":true}')],
  },
  {
    name: "happy path via proxy — reportResult(true) then SERVE",
    accounts: [{ id: "acc1", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "429-then-ok via proxy — rotate + proxy reportRateLimit ipSuspected:true (has quota)",
    accounts: [{ id: "acc1", enabled: true, cachedQuota: { pools: { "5h": { utilization: 0.5 } } } }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [resp(429, RESET_HEADERS, "rate limited"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "429-then-ok via proxy — proxy reportRateLimit ipSuspected:false (no quota)",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [resp(429, RESET_HEADERS, "rate limited"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "401 rotate then ok",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(401, jsonHeaders, "unauthorized"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "403 disable then ok",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(403, jsonHeaders, "forbidden"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "transport failure (no proxy) then ok — reportError message is the documented divergence",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [transportError(), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "transport failure via proxy → direct-retry succeeds — reportResult(false) then SERVE",
    accounts: [{ id: "acc1", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [transportError(), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "missing access token → reportError then rotate to ok",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    name: "no enabled account → terminal chatError 400",
    accounts: [],
    acquire: [null],
  },
  {
    name: "accounts only cooling down → retryable 503",
    accounts: [{ id: "acc1", enabled: true }],
    acquire: [null],
  },
  {
    name: "exhaustion — all 429, SERVE the last real upstream 429",
    accounts: Array.from({ length: N }, (_, i) => ({ id: "acc" + i, enabled: true })),
    acquire: Array.from({ length: N }, (_, i) => ({ id: "acc" + i, access: "tok" + i })),
    fetch: Array.from({ length: N }, () => resp(429, RESET_HEADERS, "rate limited")),
  },
  {
    name: "exhaustion — all transport-fail, synthetic 502",
    accounts: Array.from({ length: N }, (_, i) => ({ id: "acc" + i, enabled: true })),
    acquire: Array.from({ length: N }, (_, i) => ({ id: "acc" + i, access: "tok" + i })),
    fetch: Array.from({ length: N }, () => transportError()),
  },
];

describe("handle parity: TS path vs Java-orchestrator delegation", () => {
  for (const sc of scenarios) {
    it(sc.name, async () => {
      const { tsSnap, jvSnap, tsCalls, jvCalls } = await runBothPaths(sc);
      expect(jvSnap, "final Response must be identical").toEqual(tsSnap);
      expect(jvCalls, "ordered manager/proxy call sequence must be identical").toEqual(tsCalls);
    });
  }
});

describe("flag routing", () => {
  it("HUB_CLAUDE_JAVA_HANDLE=1 makes driver.handle delegate identically to the direct Java path", async () => {
    const sc = scenarios[0];
    // direct Java path
    resetForRun(sc);
    const direct = await snapshotResponse(
      await handleViaJavaOrchestrator(
        new Request("https://loader.local/v1/messages", { method: "POST", headers: jsonHeaders, body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }) }),
        { model: "", log: () => {} },
      ),
    );
    // via driver.handle with the env flag ON
    process.env.HUB_CLAUDE_JAVA_HANDLE = "1";
    resetForRun(sc);
    const viaFlag = await snapshotResponse(
      await driver.handle(
        new Request("https://loader.local/v1/messages", { method: "POST", headers: jsonHeaders, body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }) }),
        { model: "", log: () => {} },
      ),
    );
    delete process.env.HUB_CLAUDE_JAVA_HANDLE;
    expect(viaFlag).toEqual(direct);
  });
});
