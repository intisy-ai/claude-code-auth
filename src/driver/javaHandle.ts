// @ts-nocheck
// The claude request-serving implementation: runs the request through the TeaVM-compiled Java
// ClaudeHandleOrchestrator. Loaded lazily (dynamic import in index.ts) so the ~MB TeaVM bundle
// only evaluates on the first request, never at plugin registration. The Java orchestrator owns
// every decision; this shell owns only host I/O (fetch+IP-proxy transport, account acquire/report
// over the real manager, building the final Response). The provider-facing entry point is the
// IR-native handleIr; handleViaJavaOrchestrator is its internal transport/orchestration core.

import { proxyManager, getAutoCandidates } from "../../core-auth/dist/index.js";
import { manager } from "./index.js";
import { captureQuota, accountHasQuota } from "./accounts-controller.js";
import { getMaxAttempts, getDefaultCooldownSeconds, getMaxCooldownSeconds } from "./settings.js";
import { translators } from "../../core-ir/dist/index.js";
import { HandleIrError } from "../../core-proxy/dist/index.js";

const PROVIDER_ID = "claude-code";
const LANE = "messages"; // Claude subscription limits are account-wide (index.ts:24)

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
  // Cooldown pair drives the Java orchestrator's no-reset-header exponential backoff, byte-matching
  // request.ts:89-91's parseResetMs fallback (which the TS path applies but T6a's Java parseResetMs
  // dropped). Read per-request like maxAttempts so a config edit applies without a restart.
  const configJson = JSON.stringify({
    maxAttempts: getMaxAttempts(),
    defaultCooldownSeconds: getDefaultCooldownSeconds(),
    maxCooldownSeconds: getMaxCooldownSeconds(),
    lane: LANE,
  });

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

// ---- SP-3 T2: handleIr (IR-native entry point) ---------------------------------------------
//
// The synthetic loader-facing URL used to re-enter handleViaJavaOrchestrator from an IrRequest
// that never had a real inbound wire Request to begin with (handleIr's contract is (ir, ctx), no
// url/headers): matches AnthropicRequestTranslator.pathOf's own fallback for an absent url.
const IR_SYNTHETIC_URL = "https://loader.local/v1/messages";

// Non-2xx responses out of handleIr (real upstream "surface as-is", or one of the orchestrator's
// own SYNTHETIC error bodies: chatErrorBody/errorResponseBody) are carried out via core-proxy's
// canonical HandleIrError, not returned as data. Neither body is guaranteed to be Anthropic
// MESSAGE-shaped JSON (a SYNTHETIC body in particular can be missing the "type":"error" wrapper
// entirely, see errorResponseBody), so forcing it through translators.anthropic.decodeResponse
// would corrupt it: the codec always injects id/content/model/stop_reason keys a bare error
// envelope never had. handleIr throws HandleIrError instead, carrying the EXACT original bytes;
// the front-door (core-proxy's server.ts/Router.java) catches this typed error and reconstructs
// the response verbatim, restoring status fidelity on the IR path (T3c-1), so re-export it for
// callers that only import from this module.
export { HandleIrError };

/**
 * IR-native alternative to handleViaJavaOrchestrator (SP-3 T2): encodes the IrRequest to Anthropic
 * wire text, runs it through the EXACT SAME transport/orchestrator flow as today (via
 * handleViaJavaOrchestrator, completely unchanged), then decodes a genuine 2xx response back to
 * the canonical IR: a streamed SSE response becomes a true-streaming IrEventStream (never
 * buffered); a non-streaming response becomes an IrResponse. Any non-2xx outcome throws
 * {@link HandleIrError} rather than forcing a non-message body through the IR (see its own
 * comment above), mirroring core-proxy's own reference handleIr contract (server-ir.test.ts /
 * IrRouterTest.java), where a provider signals failure by throwing, not by returning IR.
 */
export async function handleIr(ir, ctx) {
  const bodyText = await translators.anthropic.encodeRequest(ir);
  const request = new Request(IR_SYNTHETIC_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: bodyText,
  });
  const response = await handleViaJavaOrchestrator(request, ctx);

  if (!response.ok) {
    const body = await response.text();
    throw new HandleIrError({ status: response.status, headers: Object.fromEntries(response.headers), body });
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("text/event-stream") && response.body) {
    const decodeStream = await translators.anthropic.decodeStream();
    return response.body.pipeThrough(decodeStream);
  }

  const wireText = await response.text();
  return await translators.anthropic.decodeResponse(wireText);
}
