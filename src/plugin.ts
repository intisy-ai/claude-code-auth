import { providerCapability, printAccounts } from "@intisy-ai/core-auth";
import type { Plugin, PluginContext } from "@intisy-ai/api";
import type { ProviderCapability } from "@intisy-ai/core-auth";
import type { SettingsCapability } from "@intisy-ai/core";
import { driver } from "./driver/index.js";
import { CLAUDE_SETTINGS } from "./settings.js";

const PROVIDER_ID = "claude-code";

/** What an in-process host loads: the api plugin this bundle's default export carries. */
const plugin: Plugin = {
  activate(context: PluginContext) {
    context.provide(context.capability<ProviderCapability>("provider"), providerCapability(driver));
    context.provide(context.capability<SettingsCapability>("settings"), {
      schema: () => CLAUDE_SETTINGS,
      run: async (actionId: string) => {
        if (actionId !== "accounts") return { ok: false, message: `unknown action: ${actionId}` };
        printAccounts(PROVIDER_ID, driver.accounts);
        return { ok: true };
      },
    });
  },
  deactivate() {},
};

export default plugin;
