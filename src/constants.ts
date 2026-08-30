// Claude Code OAuth constants. These are the public installed-app credentials of
// the real Claude Code CLI (extracted from the shipped binary, @anthropic-ai/
// claude-code), the same client every Claude Code user authenticates against, so
// hardcoding them is correct. The host migrated to platform.claude.com in recent
// CLI versions.

/** The public installed-app client every Claude Code user authenticates against. */
export const CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

// AUTHORIZE must be claude.ai, that host grants a Pro/Max SUBSCRIPTION token with a
// real user:inference entitlement. platform.claude.com/oauth/authorize is the Console
// surface: it mints an API-key-mode token that /v1/messages rejects with
// "OAuth token does not meet scope requirement (user:inference…)" (403). The host is
// the determinant, not the scope. Token exchange + callback stay on platform.claude.com
// (rebranded console.anthropic.com; both resolve).
/** Where a user grants the subscription token. */
export const CLAUDE_AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
/** Where an authorization code is exchanged for a token. */
export const CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
/** Where the authorize flow lands, showing the code the user pastes back. */
export const CLAUDE_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";

/** The scopes a subscription token is requested with. */
export const CLAUDE_SCOPES = ["org:create_api_key", "user:profile", "user:inference"];

// Upstream Anthropic API the OAuth (subscription) token is used against.
/** The upstream the token is used against. */
export const ANTHROPIC_API_BASE = "https://api.anthropic.com";
/** The API version every request declares. */
export const ANTHROPIC_VERSION = "2023-06-01";
// Required beta flag for OAuth-token (vs x-api-key) requests.
/** The beta flag an OAuth-token request needs, rather than an API key. */
export const ANTHROPIC_OAUTH_BETA = "oauth-2025-04-20";
// Anthropic rejects OAuth-token requests whose first system block is not this.
/** The identity block an OAuth-token request's system prompt must lead with. */
export const CLAUDE_CODE_SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude.";
