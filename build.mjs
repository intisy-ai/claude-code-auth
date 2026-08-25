// Bundles the OpenCode + Claude + CLI entries into single ESM files; the banner
// restores `require` so bundled CommonJS deps load under ESM output.
import { build } from "esbuild";

const banner = {
  js: "import { createRequire as __coreAuthCreateRequire } from 'module'; const require = __coreAuthCreateRequire(import.meta.url);",
};

// sync-bridge is an optional runtime dependency of core-auth; keep it external so
// a missing module never breaks the bundle (core-auth no-ops when it is absent).
const common = { bundle: true, platform: "node", format: "esm", banner, logLevel: "info", external: ["sync-bridge", "@intisy-ai/core", "@intisy-ai/core-auth", "@intisy-ai/core-ir", "@intisy-ai/anthropic-translator"] };

await build({ ...common, entryPoints: ["src/index.ts"], outfile: "dist/index.js" });
await build({ ...common, entryPoints: ["src/handler.ts"], outfile: "dist/handler.js" });
await build({ ...common, entryPoints: ["src/cli.ts"], outfile: "dist/cli.js" });
await build({ ...common, entryPoints: ["src/plugin.ts"], outfile: "dist/plugin.js" });
