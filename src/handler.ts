// @ts-nocheck
// Claude entry: the named handle() the claude-code-loader proxy imports for the
// claude-code provider, plus accounts + menu for the loader's account UI.

import { runProviderMenu, buildAccountMenu } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

export const handle = driver.handle;
export const handleIr = driver.handleIr; // SP-3 T2: IR-native alternative, coexists with handle()
export const accounts = driver.accounts;
export const menu = () => runProviderMenu(driver);
export const menuModel = () => buildAccountMenu(driver);   // opencode loader renders this natively in-tab
