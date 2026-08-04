# Upstream Refresh Report — 2026-08-03

Reference-clone refresh and plugin-impact analysis for the `effect-jetbrains-plugin` upstreams.
Previous refresh: 2026-07-24 (shipped as the 0.1.2 release, PR #27).
All clones live under git-ignored `.repos/` as plain clones with detached HEAD, per
`docs/reference-sources.md`; none of their contents are committed.

## Summary of refresh (old → new SHAs, date)

Refreshed on **2026-08-03** using the documented `checkout_reference` pattern (full fetch +
detached checkout; no `git pull`).

| Upstream | Local path | Old pin (2026-07-24) | New pin (2026-08-03) | Version movement |
| --- | --- | --- | --- | --- |
| Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `f75c9b92` | `431711f6` | `@effect/tsgo` **0.24.3 → 0.27.1** (52 commits) |
| Effect-TS/effect | `.repos/effect-v4` | `cea1d9c9` | `5b3ab384c` | `effect` **4.0.0-beta.101 → 4.0.0-beta.103** (249 commits) |
| effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29` | `c49b1c29` (re-verified) | unchanged |
| RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c` | `0c4f302c` (re-verified) | unchanged |
| Effect-TS/language-service | `.repos/effect-language-service` | `f26d5835` | `f26d5835` (re-verified) | unchanged |
| JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f574` | `7002f574` (re-verified) | unchanged |

Unlike the 2026-07-24 refresh (analysis first, implementation later), this refresh lands the
resulting plugin changes on the same branch — see "Actions taken" below.

## Per-upstream delta

### Effect-TS/tsgo (`f75c9b92` → `431711f6`, 52 commits, npm 0.24.3 → 0.27.1)

Releases in range: 0.25.0, 0.26.0–0.26.7, 0.27.0, 0.27.1.

- **Platform-package layout changed (0.26.0) — this was a managed-mode breaker.** The per-binary
  `lib/tsc.json` / `lib/tsc-next.json` metadata files are gone, replaced by a single
  `lib/upstream.json` profile manifest: `schemaVersion: 2`, `profiles[]` entries of `kind: "ts"`
  carrying `binName` (`tsc` / `tsc-next`) and `ts.npmVersion` / `ts.gitHead`, plus an
  `oxlint`-kind profile. Verified live against the published linux-x64 0.27.1 tarball, whose
  `lib/` now also ships `tsgolint` (~24 MB) and an Oxlint N-API binding (~16 MB) — both for the
  new Oxlint linter integration, unused by the LSP. Without adaptation, the plugin's managed
  resolution failed 0.26+ packages at both installation-health verification (which expected the
  exact old file set) and binary selection (which read only per-binary JSONs).
- **Executable bits finally stick (0.26.6).** The Changesets v3 / pnpm 10 release migration plus
  `publishConfig.executableFiles` produces tarballs with `tsc`/`tsc-next`/`tsgolint` at mode 0755
  (verified live for linux-x64 at 0.27.1; the 0.24.3-era tarballs were 0644). The plugin's
  permission restoration on extraction is now redundant for 0.26.6+ but still required for older
  versions, and is retained.
- **TypeScript pairing unchanged.** At 0.27.1 the `latest` profile is still `typescript@7.0.2`,
  gitHead `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` — exactly the real-binary verifier's pin.
  The `next` profile is `typescript@7.1.0-dev.20260803.1` (typescript-go `5b1047d1`).
- **Seven new diagnostics** (spellings verified against the shipped `schema.json`, which gained
  eight entries — `preferSchemaTypeProperty`, published since 0.24.0, only now appears there):
  `schemaLiteralNonFinite` (0.25.0, error by default), `floatingEffectInVitest` (0.25.0, error by
  default), then `abortControllerInEffect`, `catchTagToCatchReason` (v4-only, with quick fixes),
  `catchChainToFirstSuccessOf`, `preferUnsafeConstructor` (with quick fix), and
  `promiseInEffectSuccess` (warning by default) in 0.26.0 — the 0.26.0 additions default to
  suggestion unless noted.
- **Still no `workspace/executeCommand`** at 0.27.1: `ExecuteCommand` appears only in the
  `shim/lsp/lsproto` protocol shim, `_effectGetLayerMermaid` appears nowhere, and
  `internal/layergraph/mermaidurl.go` is byte-identical across 0.19.0 → 0.27.1. Hover Mermaid
  links remain the only layer-graph delivery; confirmed live (`executeCommands: []` in the
  0.27.1 verifier run).
- **New base-package surface:** an `effect-tsgo` CLI bin and a `@effect/tsgo/lib/getExePath`
  export that resolves the packaged binary matching the installed native TypeScript. The plugin
  keeps its JVM-side resolution. Upstream-bug observation: at the pin, `getExePath.js` validates
  `profile.ts.version` while the shipped manifest field is `npmVersion` — worth an upstream
  issue; the plugin parses `npmVersion` (the field that actually ships).
- Other notable changes in range with no plugin coupling: `namespaceImportPackages` completions
  for namespace reexports such as `Effect` (0.25.0), module-export identity caching for
  diagnostic performance (0.26.0), the Oxlint rule generation/documentation pipeline
  (0.26.0–0.27.1), Windows release-build fixes, and the `etsapi` → `etsgoapi` Go package rename.

### Effect-TS/effect — v4 runtime (`cea1d9c9` → `5b3ab384c`, 249 commits, beta.101 → beta.103)

- **Dev Tools wire format: compatible; constraints tightened.** The scoped diff over
  `packages/effect/src/unstable/devtools/` is limited to `DevToolsSchema.ts`: `Schema.Number` →
  `Schema.Natural` for Frequency occurrences and Histogram/Summary counts, and Summary quantile
  positions constrained to finite [0, 1]. The JSON wire encoding is unchanged, so the plugin's
  decoders need no changes — protocol regression smoke suffices. The rest of the scoped churn is
  OTLP-exporter-only (`unstable/observability/`), which the plugin does not consume.
- **Instrumentation property paths all survive** at beta.103 (grep-verified at the pin):
  `~effect/Fiber/currentFiber`, `currentStackFrame`, `_deferredInterrupt`, `mapUnsafe`,
  `addObserver`, `interruptUnsafe`, `cause.reasons`, `InterruptorStackTrace`. The plugin-relevant
  runtime files (`Fiber.ts`, `Context.ts`, `Cause.ts`, `Exit.ts`, `Tracer.ts`, `Layer.ts`,
  `References.ts`, `internal/core.ts`, `internal/effect.ts`) each have 2–9 commits in range, but
  the range is dominated by the JSDoc-category standardization (#6835), example-snippet overhaul
  (#6808), and doctest (#6789) sweeps — documentation-only rewrites.
- The `effect@4.0.0-beta.103` tag and the pinned `main` tip differ by 3 CI-only commits with an
  empty scoped diff for `unstable/devtools` + `unstable/observability`.

### Unchanged upstreams

`effect-ts/vscode-extension`, `RATIU5/zed-effect-tsgo`, `Effect-TS/language-service`, and
`JetBrains/intellij-platform-plugin-template` had zero new commits since 2026-07-24; pins
re-verified in place after a fresh fetch.

### Platform

WebStorm 2026.2.0.1 (released 2026-07-23) reports build `262.8665.341` — identical to the current
`platformVersion` pin, so no platform retarget is needed this refresh.

## Actions taken (this branch)

1. **Managed resolution for the schemaVersion-2 layout** (`binary/EffectBinaryService.kt`):
   `selectManagedBinary` and installation-health verification now read `lib/upstream.json`
   (filtering `kind: "ts"` profiles, matching the workspace TypeScript `gitHead`, resolving via
   `binName`) while keeping the per-binary-JSON path for 0.19–0.25 packages and the legacy
   `lib/tsgo` fallback; the install marker records `upstream.json`, and extra files (`tsgolint`,
   Oxlint bindings) are tolerated. Five new tests in `EffectBinaryServiceTest` cover manifest
   selection (stable + next), healthy-install reuse, manifest-deletion repair, and gitHead
   mismatch; `CURRENT_TSGO_VERSION` bumped to 0.27.1 (17/17 green).
2. **Directive completion**: the seven new rule names added to `RULE_NAMES` (verified against
   `schema.json` — the plugin list now exactly matches the shipped rule set), with test coverage.
3. **Real-binary verifier**: fixtures extended with `preferUnsafeConstructor` and
   `promiseInEffectSuccess` repros; deps bumped to `effect@4.0.0-beta.103`. Run live on
   2026-08-03 against the published linux-x64 0.27.1 `lib/tsc`: all four fixtures pass, including
   the `Replace with Scope.makeUnsafe` quick fix, hover Mermaid link, 44 completion items, and an
   empty `executeCommandProvider`.
4. **Docs**: `docs/reference-sources.md` (pins, date, canary notes rewritten), `docs/usage.md`
   (smoke targets, severity example, recorded-smoke section), and `docs/development.md` updated;
   `CHANGELOG.md` gained the 0.1.3 entry and `pluginVersion` was bumped to 0.1.3.

## Remaining follow-ups

- **IDE smoke session** (user-driven, per repo policy): managed download end-to-end on the new
  layout, one new diagnostic + quick fix in-editor, `yield*`-asterisk quickinfo, layer-graph
  hover decode, and Dev Tools metrics against `effect@4.0.0-beta.103`.
  `docs/parity-matrix.md` is intentionally untouched until that evidence exists.
- **Debugger smoke** on beta.103 (instrumentation keys survive; a behavior pass is still advised).
- Optional: file the `getExePath.js` `version`-vs-`npmVersion` bug upstream.
- Out of scope by decision: the Oxlint/tsgolint rule-runner surface (linter product, not LSP) and
  adopting `getExePath` (JVM-side resolution stays).

## Risks / testing notes

- **Watch item (unchanged from last refresh):** if npm `typescript@latest` moves past 7.0.2
  before the next `@effect/tsgo` release, future `lib/tsc` builds may stop matching workspaces
  pinned to 7.0.2; managed-mode metadata matching handles it, but the verifier pin will need
  coordinated bumps.
- **Managed download size grew** by ~40 MB unpacked per platform (tsgolint + the Oxlint binding).
  The plugin extracts the whole tarball; if cache size becomes a complaint, selective extraction
  is a possible follow-up.
- **New error-by-default diagnostics** (`schemaLiteralNonFinite`, `floatingEffectInVitest`) may
  surface as new red squiggles for users on managed LATEST mode — release-noted in 0.1.3.
- **Repo-move hygiene for future refreshes** still applies: never run unscoped log/diff across
  the effect-v4 transplant range; always path-scope.

## Explicit list of files that were updated

- `src/main/kotlin/dev/effect/intellij/binary/EffectBinaryService.kt` — upstream.json manifest
  support (selection, health check, install marker, damaged-package message).
- `src/test/kotlin/dev/effect/intellij/binary/EffectBinaryServiceTest.kt` — five manifest-layout
  tests, manifest tarball fixture, version constant bump.
- `src/main/kotlin/dev/effect/intellij/lsp/EffectDiagnosticDirectiveCompletionContributor.kt` —
  seven new rule names.
- `src/test/kotlin/dev/effect/intellij/lsp/EffectDiagnosticDirectiveCompletionTest.kt` — new-rule
  assertions.
- `src/test/testData/fixtures/lsp/new-diagnostics-workspace/` — `preferUnsafeConstructor` and
  `promiseInEffectSuccess` repros + severities.
- `scripts/verify-real-tsgo-lsp.mjs` — effect dep bump, new diagnostic + quick-fix assertions.
- `docs/reference-sources.md`, `docs/usage.md`, `docs/development.md` — pins, canary notes, smoke
  targets and recorded evidence.
- `CHANGELOG.md`, `gradle.properties` — 0.1.3.
- `UPSTREAM_REFRESH_REPORT.md` — replaced with this report.

`.repos/` contents changed on disk (two clones advanced, four re-verified) but remain git-ignored
and uncommitted. `docs/parity-matrix.md` intentionally untouched pending IDE smoke evidence.
