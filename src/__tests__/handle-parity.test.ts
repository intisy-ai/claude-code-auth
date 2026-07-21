// @ts-nocheck
// Java-orchestrator regression: runs handleViaJavaOrchestrator against scripted scenarios,
// asserting output against a frozen fixture pinning known-good behavior.

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
    outbound: [],
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

import { handleViaJavaOrchestrator, handleIr, HandleIrError } from "../driver/javaHandle.js";
import { getMaxAttempts } from "../driver/settings.js";
import expected from "./handle-scenarios.expected.json";

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
  harness.outbound = [];
}

// Capture the OUTBOUND request the orchestrator actually hands to `fetch` so the harness can
// assert the wire request produced end-to-end (url/method/headers/body + the host-set proxy)
// matches the frozen fixture exactly. Header keys are sorted (order is not wire-significant) but
// case is preserved so a genuine casing divergence would still surface.
function captureOutbound(url: any, init: any) {
  init = init || {};
  const rawHeaders = init.headers || {};
  const headers: Record<string, string> = {};
  for (const k of Object.keys(rawHeaders).sort()) headers[k] = String(rawHeaders[k]);
  return {
    url: String(url),
    method: init.method ?? null,
    headers,
    body: init.body ?? null,
    proxy: init.proxy ?? null,
  };
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

async function runJavaPath(sc: any) {
  const makeReq = () => new Request("https://loader.local/v1/messages", {
    method: "POST", headers: { "content-type": "application/json" },
    body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }),
  });
  resetForRun(sc);
  const jvResp = await handleViaJavaOrchestrator(makeReq(), { model: "", log: () => {} });
  return { snap: await snapshotResponse(jvResp), calls: normalizeCalls(harness.calls.slice()), outbound: harness.outbound.slice() };
}

// --- fixtures ---------------------------------------------------------------

const POOL_HEADERS = {
  "anthropic-ratelimit-unified-5h-utilization": "0.5",
  "anthropic-ratelimit-unified-5h-status": "allowed",
};
const RESET_HEADERS = { "anthropic-ratelimit-unified-reset": "1700000100" };

// The two paths read DIFFERENT clock primitives: the TS path calls Date.now(), while the TeaVM
// orchestrator's System.currentTimeMillis compiles to `new Date().getTime()` (NOT Date.now()).
// Freeze BOTH by swapping in a Date subclass whose no-arg construction and static now() are pinned
// to harness.now, while `new Date(arg)` still delegates to the real Date (so response/date parsing
// is unaffected). This makes the now-dependent reset times (retry-after + the no-reset-header
// exponential backoff) deterministic AND identical across paths. Captured once from the true global.
const RealDate = globalThis.Date;
class FrozenDate extends RealDate {
  constructor(...args: any[]) {
    if (args.length === 0) super(harness.now);
    else super(...(args as [any]));
  }
  static now() { return harness.now; }
}

let realFetch: any;
beforeEach(() => {
  globalThis.Date = FrozenDate as any;
  realFetch = globalThis.fetch;
  // Scripted fetch: consumes harness.fetchScript by call order; no real network is ever touched.
  // Every call's outbound (url, init) is recorded first for the wire-parity assertion.
  globalThis.fetch = (async (url: any, init: any) => {
    harness.outbound.push(captureOutbound(url, init));
    const thunk = harness.fetchScript[harness.fetchIdx++];
    if (!thunk) throw new Error("parity harness: fetch script exhausted");
    return thunk();
  }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; globalThis.Date = RealDate; vi.restoreAllMocks(); });

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
    // A 429 with no reset header. The TS path falls through parseResetMs to the exponential-backoff
    // fallback: reportRateLimit(now + 60_000*2^attempt). Both paths must produce the same numeric
    // reset (deterministic via the frozen clock).
    name: "429 with NO reset header then ok — exponential-backoff reset parity",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(429, jsonHeaders, "rate limited"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    // Same, for a 529 (Anthropic "overloaded"), also rate-limit-classified, also header-less here.
    name: "529 with NO reset header then ok — exponential-backoff reset parity",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(529, jsonHeaders, "overloaded"), resp(200, jsonHeaders, '{"ok":true}')],
  },
  {
    // Two header-less 429s in a row → proves the backoff DOUBLES per attempt across both paths
    // (attempt 0: now+60_000, attempt 1: now+120_000), still identical TS↔Java.
    name: "two 429s with NO reset header then ok — doubling backoff parity",
    accounts: [{ id: "acc1", enabled: true }, { id: "acc2", enabled: true }, { id: "acc3", enabled: true }],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }, { id: "acc3", access: "tok3" }],
    fetch: [resp(429, jsonHeaders, "rate limited"), resp(429, jsonHeaders, "rate limited"), resp(200, jsonHeaders, '{"ok":true}')],
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

describe("handle regression: Java orchestrator vs frozen fixture", () => {
  for (const sc of scenarios) {
    it(sc.name, async () => {
      const exp = expected.find((e: any) => e.name === sc.name);
      expect(exp, `fixture missing for ${sc.name}`).toBeTruthy();
      const got = await runJavaPath(sc);
      expect(got.snap, "final Response").toEqual(exp.snap);
      expect(got.calls, "ordered manager/proxy call sequence").toEqual(exp.calls);
      expect(got.outbound, "outbound fetch requests").toEqual(exp.outbound);
    });
  }
});

// --- handleIr -----------------------------------------------------------------------------------

async function collectStream(stream) {
  const reader = stream.getReader();
  const out = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    out.push(value);
  }
  return out;
}

const sampleIrRequest = (stream = false) => ({
  model: "claude-sonnet-4",
  messages: [{ role: "user", content: [{ kind: "text", text: "hi" }] }],
  stream,
});

describe("SP-3 T2: handleIr (IR-native entry point)", () => {
  it("decodes a genuine 2xx upstream message into an IrResponse", async () => {
    const sc = {
      accounts: [{ id: "acc1", enabled: true }],
      acquire: [{ id: "acc1", access: "tok1" }],
      fetch: [resp(200, { ...jsonHeaders, ...POOL_HEADERS }, JSON.stringify({
        id: "msg_1", type: "message", role: "assistant",
        content: [{ type: "text", text: "hi" }],
        model: "claude-sonnet-4", stop_reason: "end_turn", stop_sequence: null,
        usage: { input_tokens: 1, output_tokens: 1 },
      }))],
    };
    resetForRun(sc);
    const result = await handleIr(sampleIrRequest(), { model: "", log: () => {} });
    expect(result.id).toBe("msg_1");
    expect(result.stopReason).toBe("end_turn");
    expect(result.content[0]).toMatchObject({ kind: "text", text: "hi" });
  });

  it("throws HandleIrError (never a decoded IrResponse) for a SYNTHETIC no-account outcome", async () => {
    const sc = scenarios.find((s) => s.name.includes("no enabled account"));
    resetForRun(sc);
    await expect(handleIr(sampleIrRequest(), { model: "", log: () => {} }))
      .rejects.toBeInstanceOf(HandleIrError);
  });

  it("throws HandleIrError for a real (non-synthetic) non-2xx upstream response", async () => {
    const sc = scenarios.find((s) => s.name.startsWith("exhaustion — all 429"));
    resetForRun(sc);
    let caught;
    try {
      await handleIr(sampleIrRequest(), { model: "", log: () => {} });
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HandleIrError);
    expect(caught.status).toBe(429);
  });

  it("streams: a genuine 2xx SSE response becomes a true IrEventStream, never buffered", async () => {
    const sse = [
      'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":0}}}\n\n',
      'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n',
      'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}\n\n',
      'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n',
      'event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}\n\n',
      'event: message_stop\ndata: {"type":"message_stop"}\n\n',
    ].join("");
    const sc = {
      accounts: [{ id: "acc1", enabled: true }],
      acquire: [{ id: "acc1", access: "tok1" }],
      fetch: [resp(200, { "content-type": "text/event-stream" }, sse)],
    };
    resetForRun(sc);
    const result = await handleIr(sampleIrRequest(true), { model: "", log: () => {} });
    expect(result).toBeInstanceOf(ReadableStream);
    const events = await collectStream(result);
    expect(events.map((e) => e.event)).toEqual([
      "message_start", "content_block_start", "text_delta", "content_block_stop", "message_delta", "message_stop",
    ]);
    expect(events.find((e) => e.event === "text_delta").text).toBe("hi");
  });
});
