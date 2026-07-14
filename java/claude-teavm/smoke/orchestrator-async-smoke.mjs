// T6c1 PROOF — drives the TeaVM-compiled ClaudeProviderJs.handleClaudeRequestAsync with
// GENUINELY async JS fakes (real setTimeout delays before every resolve), so the two @Async
// bridges (JsAccountOpsBridge.acquire + JsAttemptExecutorBridge.execute) actually suspend/resume
// inside one CPS-transformed orchestrator loop — not synchronously-resolved promises.
//
// It asserts, for each scenario, that (a) the ordered AccountOps call sequence and (b) the final
// HandleDecision match the ground-truth snapshots from the real TS `handle`, as encoded 1:1 in
// T6b's java/claude-provider/.../ClaudeHandleOrchestratorTest.java (happyPath_okOnAttempt0…,
// rateLimitThenOk_rotatesAccount…, noEnabledAccount_terminalChatError400). Those Java tests were
// themselves written from a Node harness that reconstructed handle() verbatim (see
// task-6b-report.md). Exits non-zero on ANY mismatch.
//
// Run from repo root, AFTER `./gradlew :claude-teavm:generateJavaScript`:
//   node java/claude-teavm/smoke/orchestrator-async-smoke.mjs

import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const MODULE_PATH = join(HERE, "..", "build", "generated", "teavm", "js", "claude-provider.js");

const { handleClaudeRequestAsync } = await import(pathToFileUrl(MODULE_PATH));

function pathToFileUrl(p) {
  return "file://" + (p.startsWith("/") ? p : "/" + p.replace(/\\/g, "/"));
}

// -- helpers -----------------------------------------------------------------

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function deepEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

let failures = 0;
function assertEqual(label, actual, expected) {
  if (deepEqual(actual, expected)) {
    console.log(`    OK   ${label}`);
  } else {
    failures++;
    console.log(`    FAIL ${label}`);
    console.log(`         expected: ${JSON.stringify(expected)}`);
    console.log(`         actual:   ${JSON.stringify(actual)}`);
  }
}

const INPUTS = JSON.stringify({
  url: "https://loader.local/v1/messages",
  method: "POST",
  headers: {},
  bodyText: '{"model":"claude-code-sonnet","messages":[]}',
});

// Build the JS fakes for one scenario. `acquireScript` and `execScript` are queues; each entry is
// consumed by one call. Every fake awaits a real delay first, so suspend/resume is exercised.
function makeFakes(acquireScript, execScript, enabledCount) {
  const accountCalls = []; // ordered AccountOps calls (matches T6b RecordingAccountOps format)
  const timeline = []; // includes execute() markers — evidence of acquire/execute interleaving
  let acquireIdx = 0;
  let execIdx = 0;

  const jsAcquire = async (lane) => {
    await delay(25); // genuine async gap BEFORE resolving
    const next = acquireScript[acquireIdx++];
    if (!next) {
      accountCalls.push(`acquire(${lane}) -> null`);
      timeline.push(`acquire(${lane})=null @${Date.now() % 100000}`);
      return null; // JS null -> bridge returns Java null (no account free)
    }
    accountCalls.push(`acquire(${lane}) -> ${next.accountId}/${next.access}`);
    timeline.push(`acquire(${lane})=${next.accountId} @${Date.now() % 100000}`);
    return JSON.stringify(next);
  };

  const jsExec = async (accountId, preparedJson) => {
    await delay(35); // genuine async gap BEFORE resolving
    const r = execScript[execIdx++];
    timeline.push(`execute(${accountId}) -> status ${r.status} ref ${r.attemptRef} @${Date.now() % 100000}`);
    return JSON.stringify(r);
  };

  const jsReports = {
    reportError(accountId, attempt, message) {
      accountCalls.push(`reportError(${accountId},${attempt},"${message}")`);
    },
    reportRateLimit(accountId, lane, resetMsJson) {
      accountCalls.push(`reportRateLimit(${accountId},${lane},${JSON.parse(resetMsJson)})`);
    },
    reportSuccess(accountId) {
      accountCalls.push(`reportSuccess(${accountId})`);
    },
    disable(accountId, reason) {
      accountCalls.push(`disable(${accountId},"${reason}")`);
    },
    listEnabledCount() {
      return enabledCount;
    },
    captureQuota(accountId, headersJson) {
      accountCalls.push(`captureQuota(${accountId},${headersJson})`);
    },
  };

  return { jsAcquire, jsExec, jsReports, accountCalls, timeline };
}

async function run() {
  console.log(`module: ${MODULE_PATH}\n`);

  // ---- Scenario 1: happy path (ok on attempt 0) --------------------------------------------
  {
    console.log("Scenario 1 — happy path (async acquire -> async execute -> 200):");
    const t0 = Date.now();
    const f = makeFakes(
      [{ accountId: "acc1", access: "tok1" }],
      [{ status: 200, headers: {}, transportFailed: false, attemptRef: 0 }],
      1,
    );
    const config = JSON.stringify({ maxAttempts: 4 });
    const decision = JSON.parse(await handleClaudeRequestAsync(INPUTS, config, f.jsExec, f.jsAcquire, f.jsReports));
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join("  |  ")}`);
    console.log(`    elapsed:  ${elapsed}ms (>=60ms proves both async gaps were awaited)`);
    assertEqual("real async elapsed >= 60ms", elapsed >= 60, true);
    assertEqual("ordered AccountOps calls", f.accountCalls, [
      "acquire(messages) -> acc1/tok1",
      "captureQuota(acc1,{})",
      "reportSuccess(acc1)",
    ]);
    assertEqual("decision", decision, { kind: "SERVE", attemptRef: 0 });
  }

  // ---- Scenario 2: 429-then-ok (the key acquire->execute->acquire->execute interleaving) ----
  {
    console.log("\nScenario 2 — 429-then-ok (rotate account across the loop):");
    const t0 = Date.now();
    const f = makeFakes(
      [
        { accountId: "acc1", access: "tok1" },
        { accountId: "acc2", access: "tok2" },
      ],
      [
        { status: 429, headers: { "anthropic-ratelimit-unified-reset": "1700000100" }, transportFailed: false, attemptRef: 0 },
        { status: 200, headers: {}, transportFailed: false, attemptRef: 1 },
      ],
      2,
    );
    const config = JSON.stringify({ maxAttempts: 4 });
    const decision = JSON.parse(await handleClaudeRequestAsync(INPUTS, config, f.jsExec, f.jsAcquire, f.jsReports));
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join("  |  ")}`);
    console.log(`    elapsed:  ${elapsed}ms (>=120ms proves all FOUR async gaps were awaited)`);
    assertEqual("real async elapsed >= 120ms (2x acquire + 2x execute)", elapsed >= 120, true);
    assertEqual("interleaving order acquire,execute,acquire,execute", f.timeline.map((t) => t.split(" ")[0].replace(/\(.*/, "")), [
      "acquire",
      "execute",
      "acquire",
      "execute",
    ]);
    assertEqual("ordered AccountOps calls", f.accountCalls, [
      "acquire(messages) -> acc1/tok1",
      "captureQuota(acc1,{\"anthropic-ratelimit-unified-reset\":\"1700000100\"})",
      "reportRateLimit(acc1,messages,1700000100000)",
      "acquire(messages) -> acc2/tok2",
      "captureQuota(acc2,{})",
      "reportSuccess(acc2)",
    ]);
    assertEqual("decision (serves the 2nd attempt's ref)", decision, { kind: "SERVE", attemptRef: 1 });
  }

  // ---- Scenario 3: no-account (async acquire resolves null, listEnabledCount 0) --------------
  {
    console.log("\nScenario 3 — no-account (async acquire -> null, 0 enabled):");
    const t0 = Date.now();
    const f = makeFakes([], [], 0);
    const config = JSON.stringify({ maxAttempts: 4 });
    const decision = JSON.parse(await handleClaudeRequestAsync(INPUTS, config, f.jsExec, f.jsAcquire, f.jsReports));
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join("  |  ")}`);
    console.log(`    elapsed:  ${elapsed}ms (>=25ms proves the acquire async gap was awaited)`);
    assertEqual("real async elapsed >= 25ms", elapsed >= 25, true);
    assertEqual("ordered AccountOps calls", f.accountCalls, ["acquire(messages) -> null"]);
    assertEqual("decision kind", decision.kind, "SYNTHETIC");
    assertEqual("decision status", decision.status, 400);
    assertEqual("decision x-hub-chat-error header", decision.headers["x-hub-chat-error"], "1");
    assertEqual("decision content-type header", decision.headers["content-type"], "application/json");
    assertEqual(
      "decision body (exact chatError wording, em dash + backticked `cc auth`)",
      decision.body,
      '{"type":"error","error":{"type":"invalid_request_error","message":' +
        '"No Claude account available — all accounts are disabled or logged out. Run `cc auth` to add or re-enable one."}}',
    );
  }

  console.log("");
  if (failures > 0) {
    console.log(`RESULT: ${failures} assertion(s) FAILED`);
    process.exit(1);
  }
  console.log("RESULT: all scenarios passed — two @Async bridges composed correctly under TeaVM 0.15");
}

run().catch((e) => {
  console.error("smoke crashed:", e);
  process.exit(1);
});
