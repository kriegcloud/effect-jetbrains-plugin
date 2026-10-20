# Upstream Refresh Report — 2026-10-20

Branch: `refresh/tsgo-0.54.1-effect-4.0.5`

Plugin release build: `0.1.9`

The refresh branched from `main` at `300e7f6d`, after the 0.1.8 refresh (#51), the
"Fix all '…' problems in file" feature (#54), and Dependabot #52 (Qodana 2026.2.2), #53 (Kover
0.9.11), and #56 (Gradle wrapper 9.8.1) merged. Dependabot #55 (Kotlin 2.4.21) had a version-catalog
conflict with #53; the conflict was resolved on its branch (`bee5fdaf`, keeping both bumps) and the PR
is merged separately on the user's instruction. Those dependency updates are baseline work, not ports
from the IntelliJ template.

Kickoff targets were fixed on 2026-10-20 from live npm dist-tags (`@effect/tsgo@0.54.1`,
`effect@4.0.5`), upstream default-branch heads, and the JetBrains release feed, and were not moved
during the run.

## Reference inventory

| Reference | Previous pin | Current recorded pin | Commits | Classification |
| --- | --- | --- | ---: | --- |
| Effect tsgo | `d1e539c4956bf4b2d1643c58e1477f7597b20d5b` | `018720b8553d9198c5d69b060175ef663e3baa10` | 71 | Implement, validate, and document |
| Effect v4 | `b1d200c40a1dad69def51ebdbf0a1a612a12b8ac` | `1b740862e3d25ec5d4b6f86b9fe792b6dabbf84c` | 519 repo-wide | Validate runtime; move fixture pins; document |
| Effect VS Code extension | `64631d41a75770149361703581e923cf6971d5f4` | unchanged | 0 | Not applicable |
| Zed Effect tsgo extension | `0c4f302c861359b4f9d23f58ac146101030c6229` | unchanged | 0 | Not applicable |
| Effect language service | `690fa515f923a159638a9f78acb51a4bbccd3d36` | `cb91d700a10e86de46d57b94261d4dcc9c92fc20` | 4 | Not applicable |
| IntelliJ Platform Plugin Template | `b7b700870b23e1323353a0350a1acd9cc6d2b225` | `c7c9f997a7e67920efc534202e7ec29684d3b161` | 5 | Validation only; Kotlin 2.4.21 arrives through Dependabot #55 |

Inventory method: all six clones were clean and detached at their previous pins. Each was fetched
with prune and tags through the documented `checkout_reference` routine and detached at the target;
`HEAD` equals the recorded revision in every clone. No `git pull`, dirty-clone reset, or access to
`.repos/effect-tsgo` was needed.

The Effect commit count is graph-wide. Analysis was restricted to the DevTools sources, `Tracer.ts`,
and `internal/{core,effect,context}.ts` for the runtime fields the debugger reads; it is not a
repository-wide Effect audit.

## Delta analysis and decisions

### Effect tsgo

The published kickoff target is `@effect/tsgo@0.54.1`, published 2026-10-20. Its release tag is
`00f79e04`; the recorded pin is two commits later and carries the unpublished `preferEffectString`
rule. Releases 0.49.0 through 0.54.1 fall inside the delta.

- **Implement:** add eleven rule names to directive completion. Release metadata grows from 118 to
  129 rules: `apiStabilityLeak` (0.50.0), `effectSleepZeroToYieldNow`, `flatMapToTap`,
  `preferEffectArray`, `preferEffectRecord`, `preferSchemaTaggedError`, `schemaClassMissingBrand`
  (0.52.0), `errorPayloadShadowsName`, `preferNamespaceImport`, `preferBarrelImport` (0.53.0), and
  `schemaClassObjectLiteral` (0.54.0). No rule was removed or renamed. A release-tag snapshot
  checks all 129 completion names; `preferEffectString` is deferred as unpublished.
- **Implement and validate:** pin real-binary fixtures to 0.54.1 / Effect 4.0.5, retaining
  TypeScript 7.0.2. New live cases apply the `Replace with Effect.yieldNow` and
  `Replace with Effect.tap` quick fixes, expect the default-on `schemaClassMissingBrand` (two
  unbranded v4 classes), `schemaClassObjectLiteral` (spread copy of a branded instance), and
  `errorPayloadShadowsName` findings, and enable `preferEffectArray`, `preferEffectRecord`,
  `preferSchemaTaggedError`, and `apiStabilityLeak` through `diagnosticSeverity`, expecting warnings
  with suppression actions only. The experimental-usage assertion now matches by export name
  because the `apiStabilityLeak` case adds a second experimental export.
- **Validation only, with new regression tests:** since 0.52.0 (#830) the `latest` compiler ships
  once per platform package, as `lib/tsc` with 108 `lib.*.d.ts` files beside it;
  `artifacts/typescript/7.0.2/` is gone, while `next` (`7.1.0-dev.20261019.1`, git head
  `5237fd46`) keeps the `artifacts/typescript/<version>/tsc` layout. Stable `lib/tsc` is mode
  `0755`, 26,595,488 bytes, SHA-256
  `20a50cd37506bf52b5392903987e7490b64ca0169d2046f5ff08ce716bb91c8c`; the stable TypeScript git
  head is unchanged. The resolver already falls back to `lib/tsc` for the `latest` component when
  its artifact is absent, and the install marker records whichever executables exist, so no
  production change is needed. Tests now model the layout: standard library beside `lib/tsc`,
  healthy-install reuse without a reinstall loop, and `next` selection from `artifacts/`. The
  package retains the schema-5 manifest, provider metadata, and exact git-head matching.
- **Document:** the `maintainers` and `effect-native` presets, the `"identifier"` key pattern and
  package-less keys for `deterministicKeys`, import-style options inside per-file `overrides`,
  and removal of the `debug` option and `Flows` outline group (0.52.0; never used by the plugin)
  are upstream configuration behavior. The `relatedInformation` change (0.53.3) is in the
  `diagnostics` CLI output formats. The 0.49.0 compiler-API migration, TypeScript 7.1 paged caches,
  and the performance work change nothing observable through LSP; the five verifier lanes passed
  unchanged against 0.54.1 before any fixture edit.
- **Document and defer IDE ownership:** the package now carries Oxlint 1.87.0–1.89.0, tsgolint
  7.0.2003 / 7.0.2004, and seven compatibility profiles (Vite+ 1.1.0–1.3.0). Oxlint and tsgolint
  remain upstream-managed package contents; IDE-managed configuration and lifecycle remain deferred.
- **Validate and defer bridge work:** the stable compiler advertises no `executeCommandProvider`
  commands in any lane, and source has no `_effectGetLayerMermaid` registration. Hover Mermaid
  remains the supported graph path.

### Effect v4

The fixtures move from `effect@4.0.1` to `effect@4.0.5` (published 2026-10-15, tag `7f524e25`).
The recorded source pin is 233 commits past that tag.

- **Validation only:** `DevToolsSchema.ts` is unchanged across the delta, so the wire shape the
  plugin decodes is unchanged. `fiber.cache.span` and `fiber.cache.stackFrame` remain the span and
  stack-frame sources; the current-fiber and Context private keys are unchanged. The
  instrumentation lane passes on rc.112, rc.115, 4.0.1, and 4.0.5.
- **Document:** after the 4.0.5 tag, `DevToolsClient.ts` (#9226, #9410) pings on connect, retries a
  failed connection every second, and drains its queue on shutdown only when a writer is ready.
  These are client-side behaviors the plugin's DevTools server already tolerates. `Tracer.ts`
  gained `@stability stable` annotations only. No metrics/tracer interception or decoder change.

### Other references

- **Language service (4 commits):** missed-pipeable detection for method receivers, a
  `runEffectInsideEffect` fix for Exit runners, and Effect 4.0.5 alignment; the IDE consumes these
  through `@effect/tsgo` (0.53.5 ships the matching `runSyncExit` / `runPromiseExit` reporting).
  Not applicable.
- **IntelliJ template (5 commits):** Gradle wrapper 9.8.1 and Kotlin 2.4.21 (both via Dependabot),
  IntelliJ IDEA 2026.2.3 (this repo compiles against WebStorm 2026.2.3), a `free-disk-space` v2
  input migration (already carried), and a GPG signing test. Validation only.
- **VS Code and Zed extensions:** unchanged.
- **JetBrains builds:** the compile target stays WebStorm 2026.2.3 stable `262.10968.77` (still the
  newest 262 release). Plugin Verifier EAP pins advance from `263.6259.34` / `263.6259.32` to
  WebStorm `263.7031.26` and IntelliJ IDEA Ultimate `263.7031.23` (2026-10-15 EAPs).

## Implemented change set

1. Added eleven directive-completion names and replaced the release snapshot with the sorted
   129-name `rule-names-0.54.1.json`; the Kotlin test rejects duplicates, omissions, and
   unpublished extras.
2. Pinned LSP fixtures to Effect 4.0.5 and added the new-rule cases, including applied quick-fix
   edits, cleared findings, opt-in suppression-only checks, and unchanged baseline errors.
3. Refreshed managed-binary test inputs to tsgo 0.54.1 and the packaged next version/git head, and
   added three tests for the 0.52.0+ layout. KDoc on the resolver records the layout history. The
   synthetic archives do not claim to contain the real package bytes.
4. Moved the runtime smoke fixture to `effect@4.0.5` and extended
   `scripts/verify-instrumentation.mjs` to 4.0.5; updated `SMOKE_CHECKLIST.md`.
5. Set plugin version 0.1.9, the new EAP verifier pins, and `gradleVersion=9.8.1`.
6. Updated the six-reference provenance, canary notes, this report, changelog, README,
   getting-started/usage/development guides, and one troubleshooting line. The parity matrix is
   unchanged pending user-run smoke.

No production Kotlin or injected-instrumentation logic changed beyond the completion name list and
KDoc.

## Verification evidence

All commands ran on 2026-10-20 against the final source tree unless noted.

### Published native binary and instrumentation

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary <extracted @effect/tsgo-linux-x64@0.54.1>/lib/tsc
node scripts/verify-instrumentation.mjs
```

Results: **exit 0** for all five LSP lanes and **exit 0** for all four instrumentation versions.

- Before any fixture edit, all five LSP lanes already passed against 0.54.1 / Effect 4.0.1 with the
  binary swapped, so the existing coverage carries over unmodified.
- New diagnostics: the seven new findings are reported with the expected severities; the
  `Effect.yieldNow` and `Effect.tap` rewrites clear their findings alongside the earlier five and
  preserve the one baseline error. The four opt-in rules each offer only two suppression actions.
  Healthy, failing, directive, and fix-all lanes are unchanged and passing. No lane advertises
  execute commands; the healthy lane still returns 44 completion items.
- Instrumentation: Effect `4.0.0-rc.112`, `4.0.0-rc.115`, `4.0.1`, and `4.0.5` all pass with the
  inner-to-outer span stack `smoke.work > smoke.child > smoke.parent`, fixture source and defect
  locations, interruption, and the legacy/cache controls. Every release after rc.112 confirms the
  legacy fiber fields are absent.
- Runtime fixture: with `effect@4.0.5` installed, `node index.mjs` starts and logs the expected
  periodic child failures; the packaged `lib/tsc --noEmit` reports the eight intended Effect
  warnings in `example.ts` and no TypeScript errors. This is runtime evidence, not IDE UI proof.

### Release command

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

Result: **BUILD SUCCESSFUL in 6m 55s**.

- Tests: **136 completed, 0 failures, 0 errors, 0 skipped**.
- `buildSearchableOptions`: 284 configurables.
- Qodana for JVM 2026.1.4: **no new problems found**.
- Plugin Verifier, zero compatibility problems. The deprecated-API usage count rose from 35 to
  47–49 since the fix-all feature (#54) added `LspServerDescriptor`-family calls; it is the same
  deprecated JetBrains LSP API family, not a new warning class.

| Target | Verdict |
| --- | --- |
| WebStorm `WS-262.10968.77` | Compatible, 47 deprecated API usages |
| WebStorm `WS-263.7031.26` | Compatible, 49 deprecated API usages |
| IntelliJ IDEA Ultimate `IU-263.7031.23` | Compatible, 49 deprecated API usages |

### Distribution

- ZIP: `build/distributions/effect-jetbrains-plugin-0.1.9.zip`
- Size: 5,269,406 bytes; 13 entries; `unzip -t` reports no errors.
- SHA-256: `54b68e63cf4112f17881e4b249574ad1a7daf7556121f84738a26803ed2c696d`

### Bounded sandbox boots

`./gradlew runIde` and `./gradlew runIdeVerifierWebStorm` were started under a bounded `timeout 90`.
Both sandbox IDEs started and logged `Loaded custom plugins: … Effect TSGO (0.1.9)` — on stable
`WS-262.10968.77` and on EAP `WS-263.7031.26` — with zero SEVERE records in either log. The stable
IDE reopened the sandbox's previously indexed project and shut down cleanly after about 66 seconds
(`Watcher terminated with exit code 0`, Gradle `BUILD SUCCESSFUL in 1m 16s`); the EAP IDE shut down
cleanly after about 38 seconds (`BUILD SUCCESSFUL in 46s`). Neither boot reached the timeout, and the
sandbox `.lock`/`.port` files were removed afterwards. The boots prove plugin load on both builds,
not a sustained interactive session.

### Local installation

WebStorm was running on the desktop throughout the run (`WebStorm2026.3` Toolbox install), so
`scripts/install-local-webstorm-plugin.sh` was **not** executed; the IDE is never terminated by this
workflow. Installation is deferred until the user closes WebStorm, after which the script installs
the ZIP above. Manual smoke per `SMOKE_CHECKLIST.md` remains owed and is distinct from the automated
proof here.

## Deferred scope

- `preferEffectString` (upstream `main`, #881): unpublished at 0.54.1; add to completion when a
  release ships it.
- `preferNamespaceImport` / `preferBarrelImport` live fixture cases need `namespaceImportPackages` /
  `barrelImportPackages` configuration; they are in completion only.
- IDE-managed Oxlint/tsgolint configuration, the `_effectGetLayerMermaid` execute-command bridge,
  and `docs/parity-matrix.md` updates remain deferred pending new evidence or user-run smoke.
