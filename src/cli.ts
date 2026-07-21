// @ts-nocheck
// Standalone CLI for claude-code account management; writes to the shared
// core-auth store so accounts are used by both OpenCode and Claude Code.

import { listAccounts, removeAccount } from "../core-auth/dist/index.js";
import { login } from "./driver/login.js";

const PROVIDER_ID = "claude-code";

function printUsage() {
  process.stderr.write("usage: claude-code-auth <login [code#state]|list|remove <email>>\n");
}

async function main() {
  const [command, argument] = process.argv.slice(2);
  switch (command) {
    case "login":
      // `login` prompts for the code on the terminal; `login "<code#state>"`
      // (or the full redirect URL) completes non-interactively, works in
      // containers with no usable browser loopback.
      await login({ log: (message) => process.stdout.write(message + "\n"), code: argument });
      return;
    case "list": {
      const accounts = listAccounts(PROVIDER_ID);
      if (accounts.length === 0) {
        process.stdout.write("No Claude accounts. Run `claude-code-auth login`.\n");
        return;
      }
      for (const account of accounts) {
        const state = account.enabled === false ? " (disabled)" : "";
        process.stdout.write("- " + (account.email || account.id) + state + "\n");
      }
      return;
    }
    case "remove":
      if (!argument) { printUsage(); process.exitCode = 1; return; }
      removeAccount(PROVIDER_ID, argument);
      process.stdout.write("Removed " + argument + ".\n");
      return;
    default:
      printUsage();
      process.exitCode = 1;
  }
}

main().catch((error) => {
  process.stderr.write("Error: " + (error && error.message ? error.message : String(error)) + "\n");
  process.exitCode = 1;
});
