// @ts-nocheck
// Claude entry: the IR-native handleIr() the front-door invokes for the claude-code
// provider, plus accounts + menu for the loader's account UI.

import { providerHandlerExports } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

export const { handleIr, accounts, loginFlow, menu, menuModel, def } = providerHandlerExports(driver);
