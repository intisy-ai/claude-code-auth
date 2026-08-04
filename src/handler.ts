// @ts-nocheck
// Claude entry: the IR-native handleIr() the front-door invokes for the claude-code
// provider, plus accounts + menu for the loader's account UI.

import { providerHandlerExports, setActivityEmitter } from "../core-auth/dist/index.js";
import { emitEvent } from "../core/src/index.js";
import { driver } from "./driver/index.js";

// This bundle (dist/handler.js) is loaded independently of dist/index.js (the Claude proxy
// daemon and both apps' TUI account-menu dynamically import it directly), so it carries its
// own copy of core-auth's module-level emitter and needs its own one-time wiring.
setActivityEmitter((spec, source) => emitEvent(spec, source));

export const { handleIr, accounts, loginFlow, menu, menuModel, def } = providerHandlerExports(driver);
