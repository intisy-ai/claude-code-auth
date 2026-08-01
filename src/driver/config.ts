// @ts-nocheck
// Claude driver OAuth config. Public installed-app client → client_id only, no
// secret (the token endpoint is a public PKCE client).

import { oauthConfigFor } from "../../core-auth/dist/index.js";
import { CLAUDE_CLIENT_ID, CLAUDE_TOKEN_URL } from "../constants.js";

export function clientId() {
  return process.env.CLAUDE_CODE_CLIENT_ID || CLAUDE_CLIENT_ID;
}

export function oauthConfig() {
  return oauthConfigFor({ tokenUrl: CLAUDE_TOKEN_URL, clientId: clientId() });
}
