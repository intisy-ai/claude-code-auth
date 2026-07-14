// @ts-nocheck
// T6c2 — the DORMANT delegation shell that runs claude-code-auth's `handle` decision loop
// through the TeaVM-compiled Java orchestrator (`ClaudeHandleOrchestrator`, exported as
// `handleClaudeRequestAsync` from :claude-teavm) instead of the pure-TS path in index.ts.
//
// This module is loaded ONLY when the flag is ON (index.ts dynamically imports it inside
// `handle`), and the TeaVM ESM is loaded ONLY on the first delegated request (lazily-memoized
// dynamic import below). So with the flag OFF nothing here — and none of the ~MB of TeaVM
// output — ever evaluates: zero runtime risk to live `cc`.
//
// Split of responsibility (mirrors the orchestrator's javadoc / port-grounding-map.md):
//   - The Java orchestrator owns EVERY decision: the pre-loop body rewrite
//     (resolveAutoModel/applyAssignedModel/prepareClaudeRequest), the retry loop, the
//     status→action branching, and the final-fallback choice.
//   - This TS shell owns only host I/O: the fetch+IP-proxy transport (jsExec, reproducing
//     index.ts:100-131 verbatim), account acquisition/reporting (jsAcquire/jsReports over the
//     real `manager`), and building the final `Response` from the orchestrator's decision.
//   - NO response body ever crosses into Java: on SERVE the RETAINED live `Response` is returned
//     verbatim (SSE/stream intact); only SYNTHETIC bodies are built here from the decision JSON.

import { proxyManager, getAutoCandidates } from "../../core-auth/dist/index.js";
import { manager } from "./index.js";
import { prepareClaudeRequest } from "../plugin/request.js";
import { captureQuota, accountHasQuota } from "./accounts-controller.js";
import { getMaxAttempts } from "./settings.js";

const PROVIDER_ID = "claude-code";
const LANE = "messages"; // Claude subscription limits are account-wide (index.ts:24)

// Dormancy marker: bumped once when this (flag-ON-only) module first evaluates. Lets the
// parity/dormancy test prove the module — and the TeaVM orchestrator it pulls in — is never
// loaded while the flag is OFF. Harmless no-op counter in production.
const globalScope = globalThis as any;
globalScope.__CLAUDE_JAVA_HANDLE_MODULE_LOADS = (globalScope.__CLAUDE_JAVA_HANDLE_MODULE_LOADS || 0) + 1;

// Lazily-memoized dynamic import of the TeaVM ESM — the generated file is staged to
// src/generated/ by `core/teavm-build.mjs` at build time and bundled by esbuild (deferred).
let orchestratorPromise = null;
function loadOrchestrator() {
  if (!orchestratorPromise) orchestratorPromise = import("../generated/claude-orchestrator.teavm.js");
  return orchestratorPromise;
}

// Rebuild a Fetch `Headers` object from the header map the orchestrator hands back as JSON, so
// the real `captureQuota` (which iterates `headers.forEach`) sees the same shape as the live path.
function headersFromJson(headersJson) {
  const headers = new Headers();
  let obj;
  try { obj = headersJson ? JSON.parse(headersJson) : {}; } catch { obj = {}; }
  if (obj && typeof obj === "object") {
    for (const [name, value] of Object.entries(obj)) {
      if (value != null) headers.set(name, String(value));
    }
  }
  return headers;
}

export async function handleViaJavaOrchestrator(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});

  // Inputs the orchestrator needs; the leaderboard stays TS so its #1 candidate is passed in.
  let bodyText;
  try { bodyText = await request.clone().text(); } catch { bodyText = undefined; }
  const inputsJson = JSON.stringify({
    url: request.url,
    method: request.method,
    headers: Object.fromEntries(request.headers),
    bodyText: bodyText ?? "",
    ctxModel: (ctx && ctx.model) || "",
    topAutoCandidate: getAutoCandidates(PROVIDER_ID)[0] || "",
  });
  const configJson = JSON.stringify({ maxAttempts: getMaxAttempts(), lane: LANE });

  // --- per-request host state (isolated to this call) ---------------------------------------
  const responses = [];                 // retained live Response objects, indexed by attemptRef
  const proxyByAccount = new Map();      // proxy URL used for each account this request
  const acquiredByAccount = new Map();   // the acquired account object (fresh-lookup fallback)

  // jsAcquire — await manager.acquire(lane); null ⇔ TS `!acquired || !acquired.account`. When an
  // account exists but has no access token, return access:"" so the orchestrator's missing-access
  // branch fires with the exact "missing access token" reportError (index.ts:98), NOT the
  // no-account branch.
  const jsAcquire = async (lane) => {
    const acquired = await manager.acquire(lane);
    if (!acquired || !acquired.account) return null;
    acquiredByAccount.set(acquired.account.id, acquired.account);
    return JSON.stringify({ accountId: acquired.account.id, access: acquired.access || "" });
  };

  // jsExec — pure transport, reproducing index.ts:100-131 exactly: proxy select → fetch → on a
  // proxy fetch error reportResult(false)+retry-direct → on direct/no-proxy error return
  // transportFailed → on success reportResult(true, ms) if a proxy was used. Retains the live
  // Response host-side; only {status, headers} cross to Java. No body is ever read here.
  const jsExec = async (accountId, preparedJson) => {
    let prepared;
    try { prepared = JSON.parse(preparedJson); } catch { prepared = {}; }
    const url = prepared.request;
    const init = { method: prepared.method, headers: prepared.headers, body: prepared.body };

    const proxyUrl = proxyManager.selectForAccount(accountId, PROVIDER_ID);
    proxyByAccount.set(accountId, proxyUrl || null);
    if (proxyUrl) init.proxy = proxyUrl; // Bun fetch honors .proxy

    let response;
    const started = Date.now();
    let proxyOk = false;
    try {
      response = await fetch(url, init);
      proxyOk = !!proxyUrl;
    } catch (error) {
      if (proxyUrl) {
        proxyManager.reportResult(proxyUrl, false);
        // proxy unreachable -> retry directly (a dead proxy gives no isolation anyway)
        log("fetch via proxy " + proxyUrl + " failed: " + error + " — retrying directly");
        try {
          const directInit = { ...init };
          delete directInit.proxy;
          response = await fetch(url, directInit);
        } catch (directError) {
          log("direct retry failed: " + directError);
          return JSON.stringify({ status: 0, headers: {}, transportFailed: true, attemptRef: -1 });
        }
      } else {
        log("fetch failed: " + error);
        return JSON.stringify({ status: 0, headers: {}, transportFailed: true, attemptRef: -1 });
      }
    }
    if (proxyOk) proxyManager.reportResult(proxyUrl, true, Date.now() - started);

    const attemptRef = responses.push(response) - 1;
    return JSON.stringify({
      status: response.status,
      headers: Object.fromEntries(response.headers),
      transportFailed: false,
      attemptRef,
    });
  };

  // jsReports — the synchronous account-reporting callbacks over the real `manager`.
  const jsReports = {
    reportError(accountId, attempt, message) {
      manager.reportError(accountId, attempt, message);
    },
    // ⚠ RE-FIRE THE PROXY SIGNAL (index.ts:139-143): after manager.reportRateLimit, if a proxy
    // was used for this account, re-fire proxyManager.reportRateLimit with the ipSuspected quality
    // signal derived from the FRESH account state — the reviewer-flagged signal that must not drop.
    reportRateLimit(accountId, lane, resetMsJson) {
      const resetMs = JSON.parse(resetMsJson); // number | null
      manager.reportRateLimit(accountId, lane, resetMs);
      const proxyUrl = proxyByAccount.get(accountId);
      if (proxyUrl) {
        const fresh = manager.list().find((a) => a.id === accountId) || acquiredByAccount.get(accountId);
        proxyManager.reportRateLimit(proxyUrl, { ipSuspected: accountHasQuota(fresh) });
      }
    },
    reportSuccess(accountId) {
      manager.reportSuccess(accountId);
    },
    disable(accountId, reason) {
      manager.mutate(accountId, (a) => { a.enabled = false; a.disabledReason = reason; });
    },
    listEnabledCount() {
      return manager.list().filter((a) => a.enabled !== false).length;
    },
    captureQuota(accountId, headersJson) {
      captureQuota(manager, accountId, headersFromJson(headersJson));
    },
  };

  const { handleClaudeRequestAsync } = await loadOrchestrator();
  const decisionJson = await handleClaudeRequestAsync(inputsJson, configJson, jsExec, jsAcquire, jsReports);
  const decision = JSON.parse(decisionJson);

  if (decision.kind === "SERVE") {
    // Return the RETAINED live Response verbatim (SSE/stream intact) — no body crossed to Java.
    const retained = responses[decision.attemptRef];
    if (retained) return retained;
    // Defensive: a SERVE with no retained response should be impossible (every SERVE ref comes
    // from a jsExec success). Surface a 502 rather than throwing into the host.
    return new Response(JSON.stringify({ error: { message: "internal: serve ref not retained" } }), {
      status: 502,
      headers: { "content-type": "application/json" },
    });
  }

  // SYNTHETIC — build the body here from the decision JSON (terminal/exhaustion paths).
  return new Response(decision.body, { status: decision.status, headers: decision.headers });
}
