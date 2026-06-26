import { defineConfig } from "vitest/config";

// Only this plugin's own tests (src/) — never the bundled submodules' internal
// tests (core/, core-auth/), which run in their own repos.
export default defineConfig({
  test: { include: ["src/**/*.test.{ts,js}"] },
});
