# Evidence Slice: Upstream TSGO 0.45 And Effect rc.115 Refresh

Continuation of `07-upstream-tsgo-0.19-effect-beta97-refresh.md` and the 0.1.5 refresh recorded in
`UPSTREAM_REFRESH_REPORT.md` (2026-08-27). This slice records the September 15, 2026 inventory and
the scope agreed for plugin build `0.1.7`.

## Baseline

- `main` at `e10b1ca3` (PR #40, plugin `0.1.6`, platform line widened to `263.*`).
- Reference pins unchanged since 2026-08-27: tsgo `1ab43807`, effect `e72b12fc`; the other four
  clones (VS Code extension, Zed extension, language-service, IntelliJ template) had **zero**
  upstream commits and stay pinned.
- Human IDE smoke has not been recorded since 2026-07-10 (`docs/parity-matrix.md`); builds 0.1.2,
  0.1.5, and 0.1.6 shipped without it.

## Verified upstream deltas

- `@effect/tsgo` `latest` is `0.45.0` (2026-09-10, tag `54bbc1e7`); source main `a7414389` is 7
  commits past the tag. 56 commits since the old pin across releases 0.38.0–0.45.0.
  - Rules: 99 → 116 in source, **113 published**. 17 new (none removed or renamed, no default
    severity changes). Three (`catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`,
    `catchRefailToTapError`) are post-tag and unpublished.
  - The four rules deferred in 0.1.5 (`allOfMapToForEach`, `mapSomeToAsSome`, `catchDieToOrDie`,
    `catchConditionalRefailToCatchIf`) are now published.
  - Published linux-x64 package: still `lib/upstream.json` schema 5, same component shape, stable
    TypeScript `7.0.2` gitHead `2bd066d8` unchanged, next `7.1.0-dev.20260909.1`, executables
    0755, `lib/tsc` byte-identical to the stable artifact. No resolver change needed.
  - Still no `executeCommandProvider` / `_effectGetLayerMermaid`; hover Mermaid stays the path.
- `effect` `rc` is `4.0.0-rc.115` (2026-09-11, tag `4a05d491`); source main `ccae3542` is 31
  commits past the tag.
  - **Breaking for the debugger:** commit `9956f0e6` (in rc.113+) removes `fiber.currentSpan` and
    `fiber.currentStackFrame` in favor of `fiber.cache.span` / `fiber.cache.stackFrame`.
    `instrumentation.global.js` reads the removed fields at lines 225, 254, and 458, so span
    stacks and pause locations are lost on rc.113 or newer.
  - DevTools wire schema unchanged (`DevToolsSchema.ts`, `DevTools.ts`, `DevToolsServer.ts`).
    `DevToolsClient` now snapshots spans explicitly. Private keys `~effect/Fiber/currentFiber`
    and `~effect/Context*` unchanged. No tracer/metrics interception is justified.
- JetBrains: WebStorm stable `2026.2.2` (`262.10315.144`) already pinned. Newest 263 EAP builds
  are WS `263.4732.34` and IU `263.4732.28` (2026-09-11); verifier pins are stale at `263.3889.*`.
- Repo: five green Dependabot PRs (#36, #37, #39, #41, #42); `gradle.properties` declares
  `gradleVersion=9.5.1` while the wrapper is 9.7.0 (9.7.1 after #37).

## Decisions (grill-me, 2026-09-15)

1. **Scope:** refresh only. Issue #26 (`LspServer*` → `LspClient*`) is a separate PR after 0.1.7.
2. **Housekeeping first:** merge all five Dependabot PRs, then branch
   `refresh/tsgo-0.45.0-effect-rc115` from the resulting `main`.
3. **Debugger fix proof:** read `fiber.cache.span` / `fiber.cache.stackFrame` with fallback to the
   old fields. Prove with a new `scripts/verify-instrumentation.mjs` real-runtime lane (node, real
   `effect@rc.112` and `effect@rc.115`, instrumentation injected) plus a user paused-session check.
4. **Smoke gate before merge, one combined pass:** clears the 0.1.2/0.1.5/0.1.6 debt; parity
   matrix updated on the branch from user-reported results.
5. **Fixtures:** live cases for a representative subset (`obsoleteSchemaImport`,
   `preferSucceedSomeOrNone`, `allOfMapToForEach`, opt-in `schemaSync`) plus a committed rule-name
   snapshot from the 0.45.0 tag with a Kotlin test asserting `RULE_NAMES` covers it.
6. **Smoke app becomes a committed fixture** (DevTools client, nested spans, defect loop,
   `Effect.never` long-runner, `example.ts`, `SMOKE_CHECKLIST.md`) pinned to rc.115; shared by
   `verify-instrumentation.mjs` and the human pass.
7. **Gates:** (1) install — user closes WebStorm, plugin installed, checklist handed over;
   (2) merge — after smoke results and parity commit, stop for the user's merge call.
8. **Fixed defaults:** `pluginVersion` 0.1.7; exact `effect@4.0.0-rc.115`; EAP verifier pins
   WS `263.4732.34` / IU `263.4732.28`; compile target stays `262.10315.144`; Oxlint documented
   only; release draft, no Marketplace publish; reconcile `gradleVersion` with the wrapper.

## Candidate ledger

| Candidate | Disposition |
| --- | --- |
| Debugger span/stack-frame reads on rc.113+ | implement |
| 14 new published + 4 formerly deferred rule names in directive completion | implement |
| Real-binary fixtures to tsgo 0.45.0 / effect rc.115 (TypeScript 7.0.2 unchanged) | implement |
| `verify-instrumentation.mjs` + committed runtime fixture app | implement |
| Rule-name snapshot + coverage test | implement |
| EAP verifier pins 263.4732.x | implement |
| `gradleVersion` reconcile | implement |
| Six-pin provenance, canary notes, refresh report, changelog, usage/dev docs | document |
| CLI `diagnostics --list-files`, rule corrections, Oxlint preset movement | document |
| 3 post-tag unpublished rules | defer (canary note) |
| `_effectGetLayerMermaid` execute-command bridge | defer (no upstream registration) |
| IDE-managed Oxlint | defer (needs explicit scope expansion) |
| #26 LSP API migration | defer to separate PR |
| Manifest schema/resolver branch, private-key migration, tracer/metrics interception | not applicable |
| VS Code / Zed / language-service / template ports | not applicable (zero commits) |
