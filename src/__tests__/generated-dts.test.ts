import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const repo = fileURLToPath(new URL("../..", import.meta.url));

it("keeps the committed seam declarations identical to what the java emits", () => {
  const scratch = mkdtempSync(join(tmpdir(), "claude-code-auth-dts-"));
  execFileSync(process.execPath, [
    join(repo, "node_modules", "@intisy", "bayonet", "scripts", "emit-dts.mjs"),
    "--java-dir", repo,
    "--module", ":claude-teavm",
    "--out", scratch,
  ], { cwd: repo, stdio: "inherit" });

  const emitted = readdirSync(scratch).sort();
  expect(emitted).toEqual(["claude-orchestrator.teavm.d.ts"]);
  for (const name of emitted) {
    expect(readFileSync(join(scratch, name), "utf8"))
      .toBe(readFileSync(join(repo, "src", "generated", name), "utf8"));
  }
  // This spawns a gradle build, which vitest's five-second default cannot cover on a loaded machine.
}, 300_000);
