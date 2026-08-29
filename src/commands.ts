// @ts-nocheck
// The CLI action behind this provider's slash command, which the manifest declares and a host
// deploys. It shells back into this same bundle (`node <bundle> accounts`), so maybeRunCli runs the
// action and the process exits before the provider boots.
import { printAccounts } from "@intisy-ai/basekit/auth";
import { driver } from "./driver/index.js";

const PROVIDER_ID = "claude-code";

export async function maybeRunCli() {
  const argv = process.argv.slice(2);
  if (argv[0] === "accounts") {
    printAccounts(PROVIDER_ID, driver.accounts);
    return true;
  }
  return false;
}
