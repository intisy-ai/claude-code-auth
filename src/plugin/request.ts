// @ts-nocheck
// Rewrites an inbound Anthropic Messages request onto a selected subscription
// account: Bearer OAuth token (not x-api-key), the oauth beta flag, and the
// mandatory Claude Code system-identity block. Requests arrive already in
// Anthropic format (from the claude-code-loader proxy or the OpenCode loader),
// so no body transform is needed beyond the system block.

import {
  ANTHROPIC_API_BASE,
  ANTHROPIC_OAUTH_BETA,
  ANTHROPIC_VERSION,
  CLAUDE_CODE_SYSTEM,
} from "../constants.js";

function ensureClaudeCodeSystem(body) {
  if (!body || typeof body !== "object") return body;
  const identity = { type: "text", text: CLAUDE_CODE_SYSTEM };
  if (typeof body.system === "string") {
    body.system = body.system === CLAUDE_CODE_SYSTEM ? [identity] : [identity, { type: "text", text: body.system }];
  } else if (Array.isArray(body.system)) {
    const first = body.system[0];
    if (!(first && first.type === "text" && first.text === CLAUDE_CODE_SYSTEM)) {
      body.system = [identity, ...body.system];
    }
  } else {
    body.system = [identity];
  }
  return body;
}

function mergeBeta(existing) {
  if (!existing) return ANTHROPIC_OAUTH_BETA;
  return existing.includes(ANTHROPIC_OAUTH_BETA) ? existing : existing + "," + ANTHROPIC_OAUTH_BETA;
}

// init: { method, headers (plain object), body (string|undefined) }
export function prepareClaudeRequest(url, init, access) {
  const path = new URL(url, ANTHROPIC_API_BASE).pathname || "/v1/messages";

  let bodyText = init.body;
  let parsed;
  try { parsed = bodyText ? JSON.parse(bodyText) : undefined; } catch { parsed = undefined; }
  const streaming = !!(parsed && parsed.stream);
  if (parsed) {
    ensureClaudeCodeSystem(parsed);
    bodyText = JSON.stringify(parsed);
  }

  // lower-case the inbound header names so our overrides are unambiguous
  const headers = {};
  for (const [k, v] of Object.entries(init.headers || {})) headers[k.toLowerCase()] = v;

  delete headers["x-api-key"];
  delete headers["host"];
  delete headers["content-length"];
  delete headers["accept-encoding"];

  headers["authorization"] = "Bearer " + access;
  headers["anthropic-version"] = headers["anthropic-version"] || ANTHROPIC_VERSION;
  headers["anthropic-beta"] = mergeBeta(headers["anthropic-beta"]);
  headers["content-type"] = "application/json";

  // Node's undici rejects any body on a GET/HEAD request ("Request with GET/HEAD
  // method cannot have body") — Bun tolerated it. Omit the body for those methods.
  const method = init.method || "POST";
  const forwardInit = { method, headers, body: bodyText };
  if (method === "GET" || method === "HEAD") delete forwardInit.body;

  return {
    request: ANTHROPIC_API_BASE + path,
    init: forwardInit,
    streaming,
  };
}
