// @ts-nocheck
// Claude Code OAuth login. Everything generic (the settled guard, rebuilding a missing state,
// saving the account, the CLI prompt) comes from core-auth's defineOAuthLogin; what is left
// here is Claude's own. The redirect lands on platform.claude.com and shows a `code#state`
// string the user pastes back, so there is no localhost loopback to listen on.

import { defineOAuthLogin } from "@intisy-ai/core-auth";
import { authorizeClaude, exchangeClaude } from "../oauth/oauth.js";

export const { loginFlow, login } = defineOAuthLogin({
  provider: "claude-code",
  instructions:
    "Sign in to Claude, then copy the authorization code shown (format: code#state) and paste it here.",
  authorize: authorizeClaude,
  exchange: (code, state) => exchangeClaude(code, state),
  signInMessage:
    "Open this URL in your browser to authenticate with Claude.\nAfter approving, copy the authorization code shown on the page and paste it below\n(or re-run: claude-code-auth login \"<code#state>\").",
  pastePrompt: "Paste the authorization code (code#state) here, then Enter: ",
});
