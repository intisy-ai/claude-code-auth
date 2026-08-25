// Universal plugin contract via core's shared test-kit.
import { runPluginContract } from "@intisy-ai/core/testing";

runPluginContract({
  name: "claude-code-auth",
  entry: "dist/index.js",
  configName: "claude-code",
  app: "both",
  commands: ["claude-accounts"],
  deploy: "load",
  actions: [["accounts"]],
  readme: true,
});
