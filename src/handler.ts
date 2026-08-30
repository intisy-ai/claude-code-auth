// Claude entry: the IR-native handleIr() the front-door invokes for the claude-code
// provider, plus accounts + menu for the loader's account UI.

import { providerHandlerExports, setActivityEmitter } from "@intisy-ai/basekit/auth";
import { emitEvent, type ActivitySpec } from "@intisy-ai/basekit";
import { driver } from "./driver/index.js";

// This bundle (dist/handler.js) is loaded independently of dist/index.js (the Claude proxy
// daemon and both apps' TUI account-menu dynamically import it directly), so it carries its
// own copy of basekit/auth's module-level emitter and needs its own one-time wiring.
setActivityEmitter((spec: ActivitySpec, source: string) => emitEvent(spec, source));

/** Everything a host needs to serve and manage this provider, for one that loads only this bundle. */
export const { handleIr, accounts, loginFlow, menu, menuModel } = providerHandlerExports(driver);
