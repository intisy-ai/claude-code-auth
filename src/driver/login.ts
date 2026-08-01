// @ts-nocheck
// Claude Code OAuth login. loginFlow() is the split begin/complete form core-auth
// drives (opencode oauth method + Claude TUI menu); login() is the all-in-one CLI
// form. The redirect lands on platform.claude.com and shows a `code#state` string
// the user pastes back; there is no localhost loopback to listen on.

import {
  addAccount,
  isTTY,
  openBrowser,
  parsePastedCallback,
  awaitPaste as corePaste,
  toCoreAccount,
  encodeState,
} from "../../core-auth/dist/index.js";
import { authorizeClaude, exchangeClaude } from "../oauth/oauth.js";

const PROVIDER_ID = "claude-code";

function awaitPaste() {
  if (!isTTY()) return Promise.resolve(null);
  return corePaste("Paste the authorization code (code#state) here, then Enter: ");
}

export async function loginFlow() {
  const authorization = await authorizeClaude();
  const finish = async (cb) => {
    if (!cb || !cb.code) return null;
    // a bare pasted code has no state; rebuild it from this flow's own verifier
    const state = cb.state || encodeState({ verifier: authorization.verifier });
    const result = await exchangeClaude(cb.code, state);
    if (result.type !== "success") return null;
    const account = toCoreAccount(result);
    addAccount(PROVIDER_ID, account);
    return account;
  };
  return {
    url: authorization.url,
    instructions:
      "Sign in to Claude, then copy the authorization code shown (format: code#state) and paste it here.",
    // paste path: opencode's "code" method + the in-tab paste both pass the text.
    // Claude's redirect just displays a code (no localhost), so there is no
    // loopback to expose; pasting the code is how the flow completes.
    complete: (input) => finish(parsePastedCallback(input)),
    cancel: () => {},
  };
}

export async function login(opts) {
  const log = (opts && opts.log) || ((message) => process.stderr.write(message + "\n"));
  // opts.code: a code#state / redirect URL pasted as a CLI arg (container-friendly,
  // no TTY needed, the PKCE verifier is recovered from the pasted state).
  const pastedCode = opts && opts.code;
  const flow = await loginFlow();
  if (!pastedCode) {
    log(
      "Open this URL in your browser to authenticate with Claude:\n\n  " +
        flow.url +
        "\n\nAfter approving, copy the authorization code shown on the page and paste it below\n(or re-run: claude-code-auth login \"<code#state>\").\n",
    );
    openBrowser(flow.url);
  }
  const account = await flow.complete(pastedCode != null ? pastedCode : await awaitPaste());
  if (!account) throw new Error("login failed");
  log("Logged in" + (account.email ? " as " + account.email : "") + " and saved to the claude-code account pool.");
  return account;
}
