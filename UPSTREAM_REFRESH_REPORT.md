# Upstream Refresh Report — 2026-08-06

Reference-clone refresh and plugin-impact analysis for the `effect-jetbrains-plugin` upstreams.
Previous refresh: 2026-08-03 (shipped as the 0.1.3 release, PR #31).
All clones live under git-ignored `.repos/` as plain clones with detached HEAD, per
`docs/reference-sources.md`; none of their contents are committed.

## Summary of refresh (old → new SHAs, date)

Refreshed on **2026-08-06** using the documented `checkout_reference` pattern (full fetch +
detached checkout; no `git pull`).

| Upstream | Local path | Old pin (2026-08-03) | New pin (2026-08-06) | Version movement |
| --- | --- | --- | --- | --- |
| Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `431711f6` | `b415a1e9` | `@effect/tsgo` **0.27.1 → 0.33.0** published in 3 days (25 commits; the pinned tip is the not-yet-published 0.34.0 "Version Packages" commit, five commits past the 0.33.0 tag at `88c068b7`) |
| Effect-TS/effect | `.repos/effect-v4` | `5b3ab384c` | `4c28b0fb5` | `effect` **4.0.0-beta.103 → 4.0.0-beta.104** (139 commits; tag is 6 commits behind the pinned tip with an empty scoped diff) |
| effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29` | `c49b1c29` (re-verified) | unchanged |
| RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c` | `0c4f302c` (re-verified) | unchanged |
| Effect-TS/language-service | `.repos/effect-language-service` | `f26d5835` | `f26d5835` (re-verified) | unchanged |
| JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f574` | `7002f574` (re-verified) | unchanged |

Like the previous refresh, this one lands the resulting plugin changes on the same branch — see
"Actions taken" below.

## Per-upstream delta

### Effect-TS/tsgo (`431711f6` → `b415a1e9`, 25 commits, npm 0.27.1 → 0.33.0)

Releases in range: 0.28.0–0.33.0 published (0.34.0 cut at the pinned tip, unpublished at refresh
time).

- **Platform-package layout changed again (0.32.0, commit `52ec481`) — this was a managed-mode
  breaker.** `lib/upstream.json` jumped from `schemaVersion` 2 to **4**: the TypeScript
  `profiles[]` (with `binName` + `ts.npmVersion`/`ts.gitHead`) are replaced by `tags` (dist-tag →
  version maps per component: `typescript.latest`/`typescript.next`, `oxlint.latest`,
  `oxlint-tsgolint.latest`) and `components` (component → version → `{ gitHead, dependencies? }`),
  while `profiles[]` is **repurposed** to mean runtime-compatibility profiles (e.g. `vite-plus`).
  Executables moved to per-version directories — `artifacts/typescript/<version>/tsc(.exe)`,
  `artifacts/oxlint-tsgolint/<version>/tsgolint`, `artifacts/oxlint/<version>/*.node` — with
  `lib/tsc` kept as a byte-identical compatibility copy of the `latest` build and **`lib/tsc-next`
  removed entirely** (verified live against the published linux-x64 0.33.0 tarball). Without
  adaptation, the plugin's managed resolution hard-failed all 0.32.0+ packages: the health check's
  `schemaVersion == 2` requirement declared fresh installs damaged (forcing a reinstall loop), and
  selection then threw `DamagedManagedPackageException` because `lib/tsc` carries no adjacent
  metadata. npm `latest` is 0.33.0, so LATEST mode was broken outright.
- **`getExePath` rewritten for the new layout** (and the previously observed
  `profile.ts.version`-vs-`npmVersion` validation bug is moot — the field is no longer read): it
  now requires `schemaVersion === 4`, iterates `components.typescript`, resolves
  `artifacts/typescript/<version>/tsc`, and matches the workspace TypeScript package's `gitHead` —
  the same contract the plugin's JVM-side resolution now mirrors.
- **TypeScript pairing unchanged.** At 0.33.0 the `typescript.latest` tag is still `7.0.2`,
  gitHead `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` — exactly the real-binary verifier's pin.
  `typescript.next` is `7.1.0-dev.20260805.1` (typescript-go `12318e59`).
- **One new diagnostic:** `preferTypedSchemaDecoder` (0.33.0, `style` group, suggestion by
  default, Effect v4 only, with a `Replace with <typedName>` quick fix mapping
  `decodeUnknown*` → `decode*`). Note it is registered in `internal/rules/rules.go` but **missing
  from the shipped `schema.json`** at the pin (still 94 severity entries — an upstream
  regeneration drift worth filing).
- **New contextual quick fixes (0.31.0, `9cfdd81`):** the `floatingEffectYield` fixable offers
  `Add yield* statement` for floating Effects inside yieldable `Effect.gen` contexts, and the
  `catchTagToCatchReason` fixes now preserve narrowed wrapper error references.
- **beta.104 alignment (0.33.0, `cfea036`):** Schema class completions updated for the Effect
  `Error`/`TaggedError` renames, and auto-imports from blocked Effect internal modules are
  suppressed.
- **Still no `workspace/executeCommand`** at the new pin: `ExecuteCommand` appears only in the
  `shim/lsp/lsproto` protocol shim, `_effectGetLayerMermaid` appears nowhere, and
  `internal/layergraph/mermaidurl.go` is untouched across the range. Confirmed live
  (`executeCommands: []` in the 0.33.0 verifier run). Hover Mermaid links remain the only
  layer-graph delivery.
- **Executable bits still stick:** the 0.33.0 linux-x64 tarball ships `lib/tsc`, both
  `artifacts/typescript/*/tsc` builds, and `tsgolint` at mode 0755; the plugin's permission
  restoration stays redundant for 0.26.6+ and required for older versions (retained).
- Other notable changes in range with no plugin coupling: the Oxlint setup/integration line
  (setup CLI integration selection, Effect-aware Oxlint config schema, Vite+ compatibility,
  patched Oxlint TypeScript declarations, Effect fixes as lazy Oxlint suggestions), shim-generation
  caching, dead-code lint, and — at the unpublished 0.34.0 tip — a non-interactive `effect-tsgo
  setup` mode with explicit project/integration/diagnostic/editor/preview/apply options.

### Effect-TS/effect — v4 runtime (`5b3ab384c` → `4c28b0fb5`, 139 commits, beta.103 → beta.104)

- **Dev Tools wire format: unchanged.** The scoped diff over
  `packages/effect/src/unstable/devtools/` is limited to `DevToolsClient.ts`: the client now sends
  span **snapshots** (`{ ...span }`) instead of the live span object at span start and end, fixing
  stale in-place-mutated span states in wire payloads — a correctness improvement for the plugin's
  tracer view with zero decoder impact. `DevToolsSchema.ts` is untouched; protocol regression
  smoke suffices. Remaining scoped churn is OTLP-exporter-only (`unstable/observability/`), which
  the plugin does not consume.
- The `effect@4.0.0-beta.104` tag (`c1ed0ac9`) and the pinned `main` tip differ by 6 commits with
  an empty scoped diff for `unstable/devtools` + `unstable/observability`.

### Unchanged upstreams

`effect-ts/vscode-extension`, `RATIU5/zed-effect-tsgo`, `Effect-TS/language-service`, and
`JetBrains/intellij-platform-plugin-template` had zero new commits since 2026-08-03; pins
re-verified in place after a fresh fetch.

### Platform

No platform movement checked into this refresh; `platformVersion` stays at the WebStorm 2026.2.0.1
stable build `262.8665.341` pinned on 2026-08-03.

## Actions taken (this branch)

1. **Managed resolution for the schemaVersion-4 layout** (`binary/EffectBinaryService.kt`): the
   manifest parser now returns a sealed `UpstreamManifest` — `Profiles` (v2), `Components` (the
   v4 component shape: ordered TypeScript components, `latest` tag first, version keys restricted
   to a filename-safe character allowlist), or `Unsupported` (manifest present but
   uninterpretable). Any non-2 `schemaVersion` is parsed **by shape**, so a compatible future
   manifest revision keeps full TypeScript matching instead of being treated as unknown.
   Selection builds candidates from `artifacts/typescript/<version>/tsc`, falls back to the
   `lib/tsc` compatibility copy **only** for the `latest`-tagged version, and matches the
   workspace TypeScript `gitHead` exactly (unchanged contract). An `Unsupported` manifest fails
   closed with an actionable `EffectBinaryException` (update plugin / pin a supported version /
   use MANUAL) — mirroring upstream `getExePath`'s fail-closed behavior — while the health check
   keeps the intact install so the immutable tarball is never re-downloaded in a loop. The
   install marker records artifacts executables under package-root-relative keys, the health
   check validates the component layout and reinstalls once when a pre-0.1.4 marker never
   tracked the artifacts, and the staged-package check accepts artifacts-only future layouts.
   Ten new tests in `EffectBinaryServiceTest` cover latest/next artifact selection,
   healthy-install reuse (no reinstall loop), the `lib/tsc` fallback and its latest-only guard,
   artifact-deletion repair, gitHead mismatch listing, future-schema-by-shape parsing,
   unsupported-manifest rejection without re-download, and the pre-0.1.4 marker upgrade;
   `CURRENT_TSGO_VERSION` bumped to 0.33.0 (all green). An initial draft that degraded unknown
   schemas to an unmatched `lib/tsc` was replaced with the fail-closed design after adversarial
   review flagged it as failing open (wrong-compiler risk for `next` workspaces, no user-visible
   signal).
2. **Directive completion**: `preferTypedSchemaDecoder` added to `RULE_NAMES`, with test coverage.
3. **Real-binary verifier**: the new-diagnostics fixture gained `preferTypedSchemaDecoder`
   (typed-input `Schema.decodeUnknownSync` repro) and floating-Effect-in-`Effect.gen` repros plus
   severity config; deps bumped to `effect@4.0.0-beta.104`. Run live on 2026-08-06 against the
   published linux-x64 0.33.0 `artifacts/typescript/7.0.2/tsc`: all four fixtures pass, including
   `Replace with decodeSync`, `Add yield* statement`, hover Mermaid link, 44 completion items, and
   an empty `executeCommandProvider`.
4. **Docs**: `docs/reference-sources.md` (pins, date, canary notes rewritten for the v4 manifest
   and 0.28–0.34 range), `docs/usage.md` (smoke targets, recorded-smoke section with the new
   binary path), `docs/getting-started.md` + `docs/troubleshooting.md` + `docs/development.md`
   (artifacts layout wording); `CHANGELOG.md` gained the 0.1.4 entry and `pluginVersion` was
   bumped to 0.1.4.

## Remaining follow-ups

- **IDE smoke session** (user-driven, per repo policy): managed download end-to-end on a 0.33.0
  package (fresh cache and upgrade-over-0.2x cache), `preferTypedSchemaDecoder` + `Replace with
  decodeSync` in-editor, the `Add yield* statement` quick fix, layer-graph hover decode, and Dev
  Tools tracer smoke against `effect@4.0.0-beta.104` (span snapshots should render identically).
  `docs/parity-matrix.md` is intentionally untouched until that evidence exists — note its
  "Platform package resolution" row already lagged 0.1.3 (it still describes `tsc`/`tsc-next`
  selection) and should be rewritten for the component layout during that same pass.
- Optional: file the `schema.json` regeneration drift upstream (`preferTypedSchemaDecoder` missing
  from the shipped severity schema at 0.33.0+).
- Out of scope by decision: the Oxlint/tsgolint rule-runner surface (linter product, not LSP),
  adopting `getExePath` (JVM-side resolution stays), and the 0.34.0 non-interactive `setup` CLI
  (potential future "run setup" action; not needed for LSP parity).

## Risks / testing notes

- **Watch item (unchanged):** if npm `typescript@latest` moves past 7.0.2 before the next
  `@effect/tsgo` release, the workspace-gitHead match still handles it, but the verifier pin and
  recorded smoke need coordinated bumps.
- **Pinned-tip risk:** the tsgo pin is the unpublished 0.34.0 version-bump commit; if 0.34.0
  ships with further layout changes before the next refresh, re-verify against the published
  tarball (0.34.0's changesets are CLI/docs/CI-only, so none are expected).
- **Unknown-schema behavior is fail-closed by design:** a future `schemaVersion` that keeps the
  component shape continues to work with full gitHead matching; one that breaks the shape yields
  an actionable error (no silent wrong-compiler fallback, no re-download loop). The trade-off is
  that a shape-breaking upstream revision requires a plugin update to consume — matching upstream
  `getExePath`'s own behavior.
- **Marker-format note:** install markers from pre-0.1.4 installs of 0.32+ packages were already
  being discarded every resolve (that was the bug); markers written by 0.1.4 add
  package-root-relative keys (`artifacts/typescript/<version>/tsc`) that older plugin builds would
  reject as unknown files and reinstall once — acceptable one-way churn, no migration needed.
- **Repo-move hygiene for future refreshes** still applies: never run unscoped log/diff across
  the effect-v4 transplant range; always path-scope.

## Explicit list of files that were updated

- `src/main/kotlin/dev/effect/intellij/binary/EffectBinaryService.kt` — schemaVersion-4 manifest
  support (sealed manifest model, component candidates, artifacts-aware selection, health check,
  install marker, staged-package check, unknown-schema degrade).
- `src/test/kotlin/dev/effect/intellij/binary/EffectBinaryServiceTest.kt` — ten component-layout
  tests, components/future-schema/unsupported-schema tarball fixtures, version constant bump.
- `src/main/kotlin/dev/effect/intellij/lsp/EffectDiagnosticDirectiveCompletionContributor.kt` —
  `preferTypedSchemaDecoder` rule name.
- `src/test/kotlin/dev/effect/intellij/lsp/EffectDiagnosticDirectiveCompletionTest.kt` — new-rule
  assertion.
- `src/test/testData/fixtures/lsp/new-diagnostics-workspace/` — `preferTypedSchemaDecoder` and
  floating-Effect-in-gen repros + severity.
- `scripts/verify-real-tsgo-lsp.mjs` — effect dep bump, smoke-target comment, new diagnostic +
  quick-fix assertions.
- `docs/reference-sources.md`, `docs/usage.md`, `docs/getting-started.md`,
  `docs/troubleshooting.md`, `docs/development.md` — pins, canary notes, layout wording, smoke
  targets and recorded evidence.
- `CHANGELOG.md`, `gradle.properties` — 0.1.4.
- `UPSTREAM_REFRESH_REPORT.md` — replaced with this report.

`.repos/` contents changed on disk (two clones advanced, four re-verified) but remain git-ignored
and uncommitted. `docs/parity-matrix.md` intentionally untouched pending IDE smoke evidence.
