// @ts-nocheck
// Standalone CLI for claude-code account management; writes to the shared
// core-auth store so accounts are used by both OpenCode and Claude Code.

import { runAccountCli } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";
import { login } from "./driver/login.js";

const PROVIDER_ID = "claude-code";

function printUsage() {
  process.stderr.write("usage: claude-code-auth <login [code#state]|list|remove <email>>\n");
}

async function main() {
  // `login` prompts for the code on the terminal; `login "<code#state>"` (or the full
  // redirect URL) completes non-interactively, works in containers with no usable
  // browser loopback.
  const handled = await runAccountCli({ providerId: PROVIDER_ID, driver: { accounts: driver.accounts, login } });
  if (!handled) {
    printUsage();
    process.exitCode = 1;
  }
}

main().catch((error) => {
  process.stderr.write("Error: " + (error && error.message ? error.message : String(error)) + "\n");
  process.exitCode = 1;
});
