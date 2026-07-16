# TeaVM De-duplication — claude-code-auth (Design)

**Date:** 2026-07-16
**Status:** Approved for planning
**Sub-project of:** Part B — "There should NEVER be Java and TypeScript code doing the same thing."
**Recipe:** [[teavm-dedup-recipe]] (proven on stub-auth v1.4.0)

## Goal

Make Java the single source of truth for claude-code-auth's request-handling logic: delete the
duplicated TypeScript decision loop and the `use_java_orchestrator` flag so `handle` unconditionally
runs the TeaVM-compiled `ClaudeHandleOrchestrator`, with no behavior change and no startup
performance loss. Then republish on the continued v-lineage (`v2.3.0`, npm + jar).

## Current state (audit)

claude-code-auth is far past where stub started:

- **Java orchestrator fully built + wired.** `ClaudeHandleOrchestrator` owns every decision (pre-loop
  body rewrite `resolveAutoModel`/`applyAssignedModel`/`prepareClaudeRequest`, the retry loop, the
  status→action branching, final-fallback). `src/driver/javaHandle.ts` is the host shell (fetch+IP-proxy
  transport, account acquire/report over the real `manager`, `Response` building) wiring the
  `jsExec`/`jsAcquire`/`jsReports` seams. The JVM SPI `ClaudeProvider.handle` **already routes through
  the same orchestrator** (recipe finding #1 already satisfied).
- **Parity already proven.** `src/__tests__/handle-parity.test.ts` runs the Java path vs the live TS
  path across **16 scripted scenarios**: happy path (direct + proxy), 429/529 rotation, exponential
  backoff (no-reset-header + doubling), 401-rotate, 403-disable, transport failure (direct + proxy→
  direct-retry), missing access token, no-account terminal 400, cooling-down 503, and both exhaustion
  modes. One *documented cosmetic divergence*: the `reportError` message text on a no-proxy transport
  failure (an internal report string, not observable output — moot once the TS path is deleted).
- **Flag dormant.** `DEFAULT_USE_JAVA_ORCHESTRATOR = false`; `useJavaOrchestrator()` also honors env
  `HUB_CLAUDE_JAVA_HANDLE=1`. Dormancy proven by `handle-dormancy.test.ts`.
- **Branches:** `main` (`8e405d0`) + `experimental` (`c994e99`) both exist — NO branch migration
  needed (unlike stub). experimental carries the unified `v*` `publish.yml` (npm + jar) already.
- **core-auth submodule:** `fd30a7d` (the stale master-line commit; `098ecef` on `main` is the
  java-bearing tip, strictly additive — same as verified for stub).
- **npm:** `2.2.1` (latest tags v2.1.9/v2.2.0/v2.2.1) → next `v2.3.0`.
- **`java/gradlew` is mode `100644`** (not executable) — the unified `publish.yml` has never run;
  its first run would fail CI `npm run build` EACCES (the TeaVM step spawns gradlew on Linux). Must
  set `100755` before republish.

So no new Java is written. The work is: activate → verify live → delete duplicated TS + flag →
convert tests → republish.

## Target architecture

`src/driver/index.ts` `handle()` becomes a thin unconditional delegate:

```
async function handle(request, ctx) {
  const { handleViaJavaOrchestrator } = await import("./javaHandle.js");
  return handleViaJavaOrchestrator(request, ctx);
}
```

The dynamic `import()` is kept so the ~MB TeaVM bundle loads lazily on first request, never at plugin
registration (no startup regression — same discipline as stub).

**Deleted from `src/driver/index.ts`** (all now owned by Java): the entire TS decision loop (acquire/
retry, `prepareClaudeRequest` call site, fetch + proxy select + direct-retry, `captureQuota` call,
`isRateLimitStatus`, the 429/529/401/403 branches, exhaustion return), `resolveAutoModel`,
`applyAssignedModel`, `errorResponse` (if unused after), and `parseResetMs` import (if unused after).

**Deleted flag machinery:** `useJavaOrchestrator` + `DEFAULT_USE_JAVA_ORCHESTRATOR` +
`HUB_CLAUDE_JAVA_HANDLE` handling (`src/driver/settings.ts`), the settings "Experimental" group and
its `get`/`set` case for `use_java_orchestrator` (`src/driver/index.ts`), and the dormancy counter
(`__CLAUDE_JAVA_HANDLE_MODULE_LOADS`) in `src/driver/javaHandle.ts`.

**Kept (host shell + provider surface):** `javaHandle.ts` (now *the* handle impl), `manager`,
`fetchModels`, `login`/`loginFlow`, `accounts`, `settings` (minus the Experimental group), `models`,
`prepareClaudeRequest` (used by `javaHandle.ts`), the `driver` object, `ClaudeCodeProvider`.

**JVM SPI:** `ClaudeProvider.handle` already routes through the orchestrator — no change.

## Testing

- `handle-parity.test.ts` → convert to a **Java-path regression** test keeping all 16 scenarios:
  the scripted `jsExec`/`jsAcquire`/`jsReports` mocks stay; assert `handleViaJavaOrchestrator`
  produces the known-good expected outputs (previously asserted equal to the TS path, which is being
  removed). Preserves the full decision-surface coverage.
- `handle-dormancy.test.ts` → **delete** (dormancy is meaningless once the flag is gone).
- `contract.test.ts` → unchanged (must stay green).

## Live verification (host-side real-account smoke)

Before any deletion: build `dist/`, force the Java path (`HUB_CLAUDE_JAVA_HANDLE=1`), and drive the
BUILT `dist/handler.js` `handle` against a real seeded Claude account:

1. one real `POST /v1/messages` round-trip → expect `200` and an Anthropic-format body (proves real
   fetch + token + proxy wiring under the Java orchestrator);
2. the no-account terminal path (temp empty account store via `HUB_CONFIG_DIR`) → expect the terminal
   `400` chatError.

Requires a real Claude account configured on this machine. If unavailable, fall back to the
no-account path + the 16-scenario regression only (and surface that to the user). Delete the TS
(next step) ONLY if the real round-trip is green; re-run the smoke after deletion.

## Republish

- Set `java/gradlew` executable in git (`git update-index --chmod=+x java/gradlew`).
- Advance `core-auth` submodule `fd30a7d` → `098ecef` (main tip; strictly additive) + add
  `.gitmodules` `branch = main`. Rebuild + test to confirm additive.
- Fast-forward `main` to the experimental tip.
- Bump to `2.3.0`; run `npm run build`; ensure `git diff --exit-code -- README.md` is clean.
- Tag `v2.3.0` → the unified `publish.yml` publishes npm `claude-code-auth@2.3.0` + the
  `claude-code-auth-provider.jar` release asset (org `releases/latest` discoverable, version-aligned
  with npm — no shadowing).

## Sequencing

1. Flip `DEFAULT_USE_JAVA_ORCHESTRATOR = true` (reversible activation).
2. Live-verify (host-side real-account smoke) — GATE.
3. Delete the duplicated TS decision logic + `resolveAutoModel`/`applyAssignedModel`.
4. Remove the flag entirely; make `handle` unconditional.
5. Convert `handle-parity.test.ts` → Java regression; delete `handle-dormancy.test.ts`.
6. Re-verify live; republish (submodule advance + gradlew +x + main ff + `v2.3.0`).

## Non-goals

- Migrating antigravity-auth (next sub-project, same recipe — much larger).
- Changing observable behavior, config schema (except removing the experimental flag), account model,
  or public API.
- The provider-management dashboard UI features.

## Constraints (ecosystem)

- All plugin source TypeScript; `dist/` never committed. Java single source of truth; zero Java/TS
  logic duplication after this.
- No startup perf loss (lazy dynamic import of the TeaVM shell).
- No hardcoded OAuth secrets (claude uses `clientSecret=null`); never log tokens.
- Never override git identity; Conventional Commits; default-branch (`main`) push + republish are the
  explicitly-approved finish of this sub-project.
- Prefer few comments (non-obvious only).
- Deletion is the approved goal, gated behind the live smoke.
