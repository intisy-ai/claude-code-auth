// @ts-nocheck
// Standalone CLI for claude-code account management; writes to the shared
// core-auth store so accounts are used by both OpenCode and Claude Code.

import { runAccountCli, setActivityEmitter } from "@intisy-ai/core-auth";
import { emitEvent } from "@intisy-ai/core";
import { driver } from "./driver/index.js";
import { login } from "./driver/login.js";

// dist/cli.js is the `claude-code-auth` bin entry, a separate process/bundle from index.js and
// handler.js with its own emitter copy; login/list/remove here must also flow onto the bus.
setActivityEmitter((spec, source) => emitEvent(spec, source));

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
