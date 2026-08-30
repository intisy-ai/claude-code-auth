// Claude Code OAuth (PKCE S256). authorizeClaude() builds the authorization URL;
// exchangeClaude() trades the pasted code for refresh/access tokens. The redirect
// is platform.claude.com/oauth/code/callback, which displays a `code#state`
// string the user pastes back (Claude Code's manual flow).

import { generatePKCE } from "@openauthjs/openauth/pkce";
import { encodeState, decodeState, calculateTokenExpiry } from "@intisy-ai/basekit/auth";
import {
  CLAUDE_AUTHORIZE_URL,
  CLAUDE_CLIENT_ID,
  CLAUDE_REDIRECT_URI,
  CLAUDE_SCOPES,
  CLAUDE_TOKEN_URL,
} from "../constants.js";

/**
 * Starts a login: the URL to open, and the verifier its exchange needs.
 *
 * @returns the authorize URL and the PKCE verifier
 */
export async function authorizeClaude() {
  const pkce = await generatePKCE();
  const url = new URL(CLAUDE_AUTHORIZE_URL);
  url.searchParams.set("code", "true");
  url.searchParams.set("client_id", CLAUDE_CLIENT_ID);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("redirect_uri", CLAUDE_REDIRECT_URI);
  url.searchParams.set("scope", CLAUDE_SCOPES.join(" "));
  url.searchParams.set("code_challenge", pkce.challenge);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("state", encodeState({ verifier: pkce.verifier }));
  return { url: url.toString(), verifier: pkce.verifier };
}

/**
 * Exchanges an authorization code for a token.
 *
 * @param code - the code the user pasted back
 * @param state - the state that came with it, carrying the PKCE verifier
 * @returns the account the token belongs to, or null when the exchange failed
 */
export async function exchangeClaude(code: string, state: string) {
  try {
    const { verifier } = decodeState(state);
    const startTime = Date.now();
    const response = await fetch(CLAUDE_TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        grant_type: "authorization_code",
        client_id: CLAUDE_CLIENT_ID,
        code,
        redirect_uri: CLAUDE_REDIRECT_URI,
        code_verifier: verifier,
        state,
      }),
    });
    if (!response.ok) {
      return { type: "failed", error: await response.text().catch(() => String(response.status)) };
    }
    const payload = await response.json();
    if (!payload.refresh_token) return { type: "failed", error: "Missing refresh token in response" };
    const email =
      (payload.account && (payload.account.email_address || payload.account.email)) ||
      (payload.organization && payload.organization.name) ||
      undefined;
    return {
      type: "success",
      refresh: payload.refresh_token,
      access: payload.access_token,
      expires: calculateTokenExpiry(startTime, payload.expires_in),
      email,
    };
  } catch (error) {
    return { type: "failed", error: error instanceof Error ? error.message : "Unknown error" };
  }
}
