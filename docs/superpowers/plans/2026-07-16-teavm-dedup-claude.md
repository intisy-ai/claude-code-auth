# claude-code-auth TeaVM De-duplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Java the single source of truth for claude-code-auth's request handling: delete the duplicated TS decision loop and the `use_java_orchestrator` flag so `handle` unconditionally runs the TeaVM-compiled orchestrator, with no behavior change or startup perf loss, then republish `v2.3.0` (npm + jar).

**Architecture:** The Java `ClaudeHandleOrchestrator` + `javaHandle.ts` host shell already exist, are wired, and are proven equivalent to the live TS path by a 16-scenario parity test. This plan verifies the Java path live against a real account, deletes the now-redundant TS logic + flag, freezes the 16 scenarios as a Java-path regression, and republishes.

**Tech Stack:** TypeScript (esbuild bundle), TeaVM-compiled Java (already built), vitest, Gradle, GitHub Actions.

## Global Constraints

- Java is the single source of truth; zero Java/TS logic duplication after this. — spec "Goal"
- No startup perf loss: keep the dynamic `import("./javaHandle.js")` so the TeaVM bundle loads lazily, never at registration. — spec "Target architecture"
- No behavior change (observable Response + manager/proxy call sequence must be preserved). — spec "Testing"
- No hardcoded OAuth secrets (claude uses `clientSecret=null`); never log tokens. — spec "Constraints"
- Never override git identity; Conventional Commits; `main` push + `v2.3.0` republish are the approved finish. — spec "Constraints"
- Prefer few comments (non-obvious only). — spec "Constraints"
- Deletion is gated behind the live smoke (Task 1). — spec "Sequencing"

---

## File structure

- `src/driver/index.ts` — **modify**: `handle()` → unconditional lazy delegate; delete TS decision loop, `resolveAutoModel`, `applyAssignedModel`, `isRateLimitStatus`, `errorResponse` (if unused), the settings "Experimental" group + its get/set case. Drop the `useJavaOrchestrator` import and the `parseResetMs` import if unused.
- `src/driver/settings.ts` — **modify**: delete `DEFAULT_USE_JAVA_ORCHESTRATOR`, `useJavaOrchestrator`, the `HUB_CLAUDE_JAVA_HANDLE` read.
- `src/driver/javaHandle.ts` — **modify**: delete the dormancy counter (`__CLAUDE_JAVA_HANDLE_MODULE_LOADS`).
- `src/__tests__/handle-parity.test.ts` — **rewrite** into a Java-path regression (keep all 16 scenarios + harness; assert against a committed fixture).
- `src/__tests__/handle-scenarios.expected.json` — **create** (committed fixture captured from the current Java path).
- `src/__tests__/handle-dormancy.test.ts` — **delete**.
- `java/gradlew` (mode), `core-auth` gitlink, `.gitmodules`, `package.json` (version) — **modify** in Task 3.

---

### Task 1: Live verification GATE (host-side real-account smoke) — controller-driven

**Files:** none committed (verification only). Uses `scratchpad/claude-live-verify.mjs`.

> Driven by the controller (not a subagent). No deletion happens until this is green.

- [ ] **Step 1: Build the current tree**

Run: `npm run build`
Expected: `dist/handler.js` emitted, no errors. (Flag still present; default OFF.)

- [ ] **Step 2: Write the verify script** `scratchpad/claude-live-verify.mjs`

```javascript
import { pathToFileURL } from "node:url";
import { join } from "node:path";
const REPO = "F:/Documents/GitHub/ai/providers/claude-code-auth";
const { handle } = await import(pathToFileURL(join(REPO, "dist/handler.js")).href);
const body = JSON.stringify({ model: "claude-code-sonnet", max_tokens: 16, messages: [{ role: "user", content: "Reply with the single word: pong" }] });
const req = new Request("https://loader.local/v1/messages", { method: "POST", headers: { "content-type": "application/json" }, body });
const res = await handle(req, { model: "", log: () => {} });
const text = await res.text();
console.log(JSON.stringify({ status: res.status, ct: res.headers.get("content-type"), bodyHead: text.slice(0, 300) }));
```

- [ ] **Step 3: Real round-trip through the Java path** (real account, real Anthropic)

Run (points config at the real cc home so a real account is used; forces the Java path):
```bash
HUB_CLAUDE_JAVA_HANDLE=1 HUB_CONFIG_DIR="$HOME/.claude" node scratchpad/claude-live-verify.mjs
```
Expected: `status: 200`, `ct: application/json` (or `text/event-stream` if streamed), body is an Anthropic message (contains `"type":"message"` / `"content"`). This proves real fetch + token refresh + proxy wiring under the Java orchestrator.
If no real account is configured (401/"no account"), fall back to Step 4 only and surface to the user that the real round-trip was not exercised.

- [ ] **Step 4: No-account terminal path** (no real call)

Run:
```bash
CFG=$(mktemp -d); HUB_CLAUDE_JAVA_HANDLE=1 HUB_CONFIG_DIR="$CFG" node scratchpad/claude-live-verify.mjs
```
Expected: `status: 400`, body is the terminal chatError ("No Claude account available…"). Proves the orchestrator's no-account branch through the built bundle.

- [ ] **Step 5: Gate**

Only proceed to Task 2 if Step 3 (or, if no account, Step 4) is green. Record the observed status/body head for the Task 2 commit body.

---

### Task 2: Delete the duplicated TS + flag; freeze the 16 scenarios as a Java regression

**Files:**
- Modify: `src/driver/index.ts`, `src/driver/settings.ts`, `src/driver/javaHandle.ts`
- Rewrite: `src/__tests__/handle-parity.test.ts`
- Create: `src/__tests__/handle-scenarios.expected.json`
- Delete: `src/__tests__/handle-dormancy.test.ts`

**Interfaces:**
- Consumes: `handleViaJavaOrchestrator(request, ctx)` from `./javaHandle.js` (unchanged export).
- Produces: `driver.handle` now unconditionally delegates to the Java orchestrator; no flag exports remain.

- [ ] **Step 1: Confirm the parity baseline is green BEFORE touching anything**

Run: `npx vitest run src/__tests__/handle-parity.test.ts`
Expected: PASS (all 16 scenarios + flag-routing) — this is the TS≡Java evidence we freeze.

- [ ] **Step 2: Capture the fixture from the current Java path**

Add a temporary capture branch to `handle-parity.test.ts`: inside the scenario loop, when `process.env.CAPTURE_FIXTURE === "1"`, collect `{ name: sc.name, snap: jvSnap, calls: jvCalls, outbound: jvOutbound }` into an array and, in an `afterAll`, write it to `src/__tests__/handle-scenarios.expected.json` (pretty-printed). Run:
```bash
CAPTURE_FIXTURE=1 npx vitest run src/__tests__/handle-parity.test.ts
```
Expected: `handle-scenarios.expected.json` written with 16 entries. Then remove the temporary capture branch. (The fixture is the frozen expected Java output; it equals the just-proven TS output.)

- [ ] **Step 3: Rewrite `handle-parity.test.ts` as a Java-path regression**

Keep the entire harness (the `vi.hoisted` fakes, `vi.mock`, `resetForRun`, `captureOutbound`, `normalizeCalls`, `snapshotResponse`, `FrozenDate`, the `beforeEach`/`afterAll`, the scenario table). Replace `runBothPaths` with a Java-only runner and assert against the fixture:

```typescript
import expected from "./handle-scenarios.expected.json";

async function runJavaPath(sc: any) {
  const makeReq = () => new Request("https://loader.local/v1/messages", {
    method: "POST", headers: { "content-type": "application/json" },
    body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }),
  });
  resetForRun(sc);
  const jvResp = await handleViaJavaOrchestrator(makeReq(), { model: "", log: () => {} });
  return { snap: await snapshotResponse(jvResp), calls: normalizeCalls(harness.calls.slice()), outbound: harness.outbound.slice() };
}

describe("handle regression: Java orchestrator vs frozen fixture", () => {
  for (const sc of scenarios) {
    it(sc.name, async () => {
      const exp = expected.find((e: any) => e.name === sc.name);
      expect(exp, `fixture missing for ${sc.name}`).toBeTruthy();
      const got = await runJavaPath(sc);
      expect(got.snap, "final Response").toEqual(exp.snap);
      expect(got.calls, "ordered manager/proxy call sequence").toEqual(exp.calls);
      expect(got.outbound, "outbound fetch requests").toEqual(exp.outbound);
    });
  }
});

describe("driver.handle delegates to the Java orchestrator", () => {
  it("driver.handle == handleViaJavaOrchestrator for the happy path", async () => {
    const sc = scenarios[0];
    resetForRun(sc);
    const direct = await snapshotResponse(await handleViaJavaOrchestrator(
      new Request("https://loader.local/v1/messages", { method: "POST", headers: jsonHeaders, body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }) }),
      { model: "", log: () => {} }));
    resetForRun(sc);
    const viaDriver = await snapshotResponse(await driver.handle(
      new Request("https://loader.local/v1/messages", { method: "POST", headers: jsonHeaders, body: sc.body ?? JSON.stringify({ model: "claude-sonnet-4", messages: [] }) }),
      { model: "", log: () => {} }));
    expect(viaDriver).toEqual(direct);
  });
});
```

Delete the old `runBothPaths` and the old `describe("handle parity …")` + `describe("flag routing" …)` blocks (the flag no longer exists). Update the file's top comment to describe the regression purpose (keep it short). `getMaxAttempts` import stays (used by the scenario table via `N`).

- [ ] **Step 4: Delete the dormancy test**

Run: `git rm src/__tests__/handle-dormancy.test.ts`

- [ ] **Step 5: Make `handle` unconditional in `src/driver/index.ts`**

Replace the entire `async function handle(request, ctx) { … }` body with:
```typescript
async function handle(request, ctx) {
  const { handleViaJavaOrchestrator } = await import("./javaHandle.js");
  return handleViaJavaOrchestrator(request, ctx);
}
```
Then DELETE (now dead): `resolveAutoModel`, `applyAssignedModel`, `isRateLimitStatus`, `errorResponse` (only if no remaining reference), and the `PROVIDER_ID`/`LANE` consts only if unused after (check — they may be unused once the loop is gone; delete only if unreferenced). Remove the `useJavaOrchestrator` name from the `./settings.js` import and remove the `parseResetMs` import from `../plugin/request.js` if it is no longer referenced. Keep `prepareClaudeRequest` import ONLY if still referenced in this file (it is used by `javaHandle.ts`, not necessarily here — remove from index.ts if unused there).

In the `driver.settings` object, DELETE the entire "Experimental" group (the `use_java_orchestrator` field) and, in `settings.get`, delete the `if (key === "use_java_orchestrator") …` line.

- [ ] **Step 6: Remove the flag from `src/driver/settings.ts`**

Delete `const DEFAULT_USE_JAVA_ORCHESTRATOR = false;`, the entire `useJavaOrchestrator()` function, and its doc comment (the `HUB_CLAUDE_JAVA_HANDLE` read lives only there).

- [ ] **Step 7: Remove the dormancy counter from `src/driver/javaHandle.ts`**

Delete the `globalScope`/`__CLAUDE_JAVA_HANDLE_MODULE_LOADS` lines and their comment. Leave the rest of `javaHandle.ts` unchanged (it is now the sole handle implementation).

- [ ] **Step 8: Build + full test suite**

Run: `npm run build && npx vitest run`
Expected: PASS — the regression test (16 + delegate), `contract.test.ts`, and any other suites green. No references to deleted symbols remain (grep `useJavaOrchestrator`, `resolveAutoModel`, `applyAssignedModel`, `HUB_CLAUDE_JAVA_HANDLE`, `use_java_orchestrator`, `__CLAUDE_JAVA_HANDLE` → only allowed hit is inside `handle-scenarios.expected.json` data or none).

- [ ] **Step 9: Re-verify live (controller)**

Re-run Task 1 Steps 3–4 (now the default path IS Java, no env var needed):
```bash
HUB_CONFIG_DIR="$HOME/.claude" node scratchpad/claude-live-verify.mjs
```
Expected: same 200 (or streamed) real round-trip; no-account temp dir → 400.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(claude): delete duplicated TS handle loop + use_java_orchestrator flag — Java is the single source

Java path live-verified (real account round-trip 200 + no-account 400); 16-scenario regression frozen from the proven parity baseline."
```

---

### Task 3: Republish `v2.3.0` (submodule reconcile + gradlew fix + main ff + tag) — controller-driven

**Files:** `java/gradlew` (mode), `core-auth` gitlink, `.gitmodules`, `package.json`.

- [ ] **Step 1: Mark gradlew executable** (CI `npm run build` TeaVM step spawns it on Linux)

```bash
git update-index --chmod=+x java/gradlew
git commit -m "fix(claude): mark java/gradlew executable (100755) for CI TeaVM build"
```

- [ ] **Step 2: Advance the core-auth submodule to the main java-bearing tip**

```bash
cd core-auth && git fetch origin && git checkout 098ecef51e21483863a9836e9e15f917669aea6a && cd ..
git add core-auth
```
Add `branch = main` under `[submodule "core-auth"]` in `.gitmodules`, then:
```bash
git add .gitmodules
git commit -m "chore(claude): advance core-auth submodule fd30a7d -> main (098ecef, strictly additive) + track main"
```

- [ ] **Step 3: Verify additive (build + tests)**

Run: `npm run build && npx vitest run`
Expected: PASS (the bump adds the Java accounts module + LiveStore/lock fixes; TS bundle unaffected).

- [ ] **Step 4: Bump version + README drift check**

```bash
npm version 2.3.0 --no-git-tag-version
npm run build
git diff --exit-code -- README.md   # must be clean; if it changed, `git add README.md`
git add package.json
git commit -m "chore(claude): release v2.3.0"
```

- [ ] **Step 5: Push experimental, fast-forward main, tag**

```bash
git push origin experimental
git push origin experimental:main
git tag v2.3.0 && git push origin v2.3.0
```

- [ ] **Step 6: Verify CI published both artifacts**

Watch the run: `gh run watch <id> --repo intisy-ai/claude-code-auth --exit-status`
Confirm: `npm view claude-code-auth version` → `2.3.0`; `gh api repos/intisy-ai/claude-code-auth/releases/latest --jq '{tag:.tag_name, asset:.assets[0].name}'` → asset `claude-code-auth-provider.jar` at the aligned version. If the run fails, diagnose from `gh run view <id> --log-failed` (expect the same class of fixable CI issues seen on stub).

---

## Self-Review

**1. Spec coverage:**
- Target architecture (unconditional lazy delegate; delete TS loop + resolveAutoModel/applyAssignedModel + flag machinery + dormancy counter) → Task 2 Steps 5–7. ✓
- Testing (parity→Java regression keeping 16 scenarios; delete dormancy) → Task 2 Steps 2–4. ✓
- Live verification (host-side real-account smoke) → Task 1 + Task 2 Step 9. ✓
- Republish (gradlew +x, core-auth→main, main ff, v2.3.0 npm+jar) → Task 3. ✓
- No startup perf loss (dynamic import kept) → Task 2 Step 5. ✓

**2. Placeholder scan:** No TBD/TODO. Deletions specify exact symbols; the "if unused" guards (errorResponse/parseResetMs/prepareClaudeRequest/PROVIDER_ID/LANE) are explicit grep-and-remove instructions, not vague hand-waving — the implementer verifies references before removing.

**3. Type consistency:** `handleViaJavaOrchestrator(request, ctx)` matches its existing export; `runJavaPath` returns `{snap, calls, outbound}` matching the fixture entry shape `{name, snap, calls, outbound}`; `normalizeCalls`/`snapshotResponse`/`resetForRun`/`scenarios`/`harness`/`jsonHeaders` all reused from the retained harness. ✓
