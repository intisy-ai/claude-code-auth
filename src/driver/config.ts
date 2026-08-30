// Claude driver OAuth config. Public installed-app client → client_id only, no
// secret (the token endpoint is a public PKCE client).

import { oauthConfigFor } from "@intisy-ai/basekit/auth";
import { CLAUDE_CLIENT_ID, CLAUDE_TOKEN_URL } from "../constants.js";

/**
 * The OAuth client to authenticate as.
 *
 * @returns the configured client id, or the public installed-app one
 */
export function clientId(): string {
  return process.env.CLAUDE_CODE_CLIENT_ID || CLAUDE_CLIENT_ID;
}

/**
 * This provider's OAuth configuration, as the account engine takes it.
 *
 * @returns the token endpoint and client id, with no secret: the endpoint is a public PKCE client
 */
export function oauthConfig() {
  return oauthConfigFor({ tokenUrl: CLAUDE_TOKEN_URL, clientId: clientId() });
}
