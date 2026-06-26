// Universal plugin contract via core's shared test-kit.
import { runPluginContract } from "../../core/src/testing.js";

runPluginContract({
  name: "claude-code-auth",
  entry: "dist/index.js",
  configName: "claude-code-auth",
  app: "both",
  commands: ["claude-code-auth-config", "claude-accounts"],
  deploy: "load",
  actions: [["accounts"]],
});
