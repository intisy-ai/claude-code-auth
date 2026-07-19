// @ts-nocheck
// Claude entry: the IR-native handleIr() the front-door invokes for the claude-code
// provider, plus accounts + menu for the loader's account UI.

import { runProviderMenu, buildAccountMenu } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

export const handleIr = driver.handleIr; // IR-native serving path (front-door owns app<->IR)
export const accounts = driver.accounts;
export const menu = () => runProviderMenu(driver);
export const menuModel = () => buildAccountMenu(driver);   // opencode loader renders this natively in-tab
