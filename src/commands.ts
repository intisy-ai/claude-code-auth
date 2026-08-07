// @ts-nocheck
// Cross-app slash-commands for claude-code-auth. Config name matches the package
// (claude-code-auth.json). The account command is namespaced (/claude-accounts)
// so it never collides with the other providers' account commands.
import { configCommand, runConfigCli } from "@intisy-ai/core";
import { printAccounts } from "@intisy-ai/core-auth";
import { driver } from "./driver/index.js";

const PROVIDER_ID = "claude-code";

export const CLAUDE_COMMANDS = [
  configCommand("claude-code-auth"),
  {
    name: "claude-accounts",
    description: "List signed-in Claude subscription accounts",
    shell: 'node "{{BUNDLE}}" accounts',
    body: "Above are the Claude subscription accounts and their enabled state. Report them; if none, tell the user to add one (oc auth login → Claude, or the loader login flow).",
  },
];

export async function maybeRunCli(configName) {
  const argv = process.argv.slice(2);
  if (argv[0] === "config") {
    runConfigCli(configName, argv.slice(1));
    return true;
  }
  if (argv[0] === "accounts") {
    printAccounts(PROVIDER_ID, driver.accounts);
    return true;
  }
  return false;
}
