import type { Plugin, PluginContext } from "@intisy-ai/api";
import type { Provider, ProviderSupport } from "@intisy-ai/core-auth";
import type { SettingsCapability } from "@intisy-ai/core";
import { driver } from "./driver/index.js";
import { CLAUDE_SETTINGS } from "./settings.js";

const PROVIDER_ID = "claude-code";

// The id the manifest states under services.consumes. Named here rather than imported, because
// importing it would link the library this service exists to keep out of the bundle.
const PROVIDER_SUPPORT = "provider-support";

/**
 * What an in-process host loads: the api plugin this bundle's default export carries.
 *
 * @remarks
 * The provider helpers come from the host rather than from an import, so this bundle carries no
 * copy of the library that implements them. A host offering none cannot run a provider at all, so
 * the throw names the service instead of leaving the capability silently unprovided.
 */
const plugin: Plugin = {
  activate(context: PluginContext) {
    const support = context.services.get(context.service<ProviderSupport>(PROVIDER_SUPPORT));
    if (!support) throw new Error(`this host offers no "${PROVIDER_SUPPORT}" service, so it cannot run a provider`);
    context.provide(context.capability<Provider>("provider"), support.capability(driver));
    context.provide(context.capability<SettingsCapability>("settings"), {
      schema: () => CLAUDE_SETTINGS,
      run: async (actionId: string) => {
        if (actionId !== "accounts") return { ok: false, message: `unknown action: ${actionId}` };
        support.printAccounts(PROVIDER_ID, driver.accounts);
        return { ok: true };
      },
    });
  },
  deactivate() {},
};

export default plugin;
