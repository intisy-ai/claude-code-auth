import { PROVIDER, providerCapability } from "@intisy-ai/core-auth";
import type { Plugin, PluginContext } from "@intisy-ai/api";
import { driver } from "./driver/index.js";

/** What an in-process host loads: the api plugin this bundle's default export carries. */
const plugin: Plugin = {
  activate(context: PluginContext) {
    context.provide(PROVIDER, providerCapability(driver));
  },
  deactivate() {},
};

export default plugin;
