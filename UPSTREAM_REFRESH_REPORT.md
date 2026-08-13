# Upstream Refresh Report — 2026-08-13

Reference-clone refresh and plugin-impact analysis for the `effect-jetbrains-plugin` upstreams.
Previous refresh: 2026-08-06 (shipped as the 0.1.4 release, PR #33).
All clones live under git-ignored `.repos/` as plain clones with detached HEAD, per
`docs/reference-sources.md`; none of their contents are committed.

## Summary of refresh (old → new SHAs, date)

Refreshed on **2026-08-13** using the documented `checkout_reference` pattern (full fetch +
detached checkout; no `git pull`).

| Upstream | Local path | Old pin (2026-08-06) | New pin (2026-08-13) | Version movement |
| --- | --- | --- | --- | --- |
| Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `b415a1e9` | `ca311a5c` | `@effect/tsgo` **0.33.0 → 0.36.4** (22 commits; releases 0.34.0–0.36.4, all published by 2026-08-10; the pinned tip is 1 commit past the 0.36.4 tag at `ca859c50` and advances `typescript.next`/Oxlint upstream metadata plus a type-only-heritage execution-flow fix — `internal/typeparser/execution_flow.go`, 7 refreshed Mermaid test baselines, and a rebased typescript-go patch) |
| Effect-TS/effect | `.repos/effect-v4` | `4c28b0fb5` | `7018f9668` | `effect` **4.0.0-beta.104 → 4.0.0-rc.108** (98 commits through beta.105/106/107 and the beta→rc transition; the rc.108 tag `bef7bf38` is 16 commits behind the pinned tip with an empty scoped diff) |
| effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29` (documented) | `64631d41` | **0.9.0 → 0.10.0, "sketch v4 support"** (2 commits, 2026-08-07 — the first movement since 2025-11-28; the clone was found already checked out at the new tip while the docs table still recorded the old pin, now reconciled) |
| RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c` | `0c4f302c` (re-verified) | unchanged |
| Effect-TS/language-service | `.repos/effect-language-service` | `f26d5835` | `5e4d380b` | 0.87.1 → 0.87.2 (3 commits: dependency bumps to Effect beta.104 then beta.107 plus Version Packages; historical-comparison clone only, no plugin coupling) |
| JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f574` | `7002f574` (re-verified) | unchanged |

Unlike the two previous refreshes, this one required **zero production-code changes** — the delta
landed entirely in tests, the verifier, and docs (see "Actions taken").

## Per-upstream delta

### Effect-TS/tsgo (`b415a1e9` → `ca311a5c`, 22 commits, npm 0.33.0 → 0.36.4)

- **Package layout and manifest: unchanged.** `schemaVersion` stays 4 with the same
  `tags`/`components`/runtime-compat-`profiles` shape and the same three components
  (`typescript`, `oxlint-tsgolint`, `oxlint`); `lib/getExePath.js` and the package assembler
  (`_tools/repoctl/src/packages.ts`) diff empty across the range. Verified live against the
  published linux-x64 0.36.4 tarball: `artifacts/typescript/<version>/tsc` for both builds,
  `lib/tsc` byte-identical (size and SHA-256) to the `latest` artifact, no `lib/tsc-next`, all
  executables 0755, platform-package top-level layout identical to 0.33.0. The plugin's
  schemaVersion-4 managed resolution needs no adaptation; the only structural addition in the
  range is an LSP-irrelevant `oxlint-presets/` directory of Oxlint config presets shipped in the
  **base** `@effect/tsgo` package since 0.36.0 (the platform packages are unaffected).
- **Diagnostics: none added, removed, or renamed** (`internal/rules/rules.go` and the fixable
  implementations diff empty across the range). The 0.1.4 follow-up about `schema.json`
  regeneration drift is **resolved upstream**: 0.36.0 (`8423f68`) regenerated the severity schema
  to 95 entries, adding the missing `preferTypedSchemaDecoder`. The plugin's `RULE_NAMES` already
  matches the regenerated schema exactly (95/95), so directive completion needs no change.
- **Behavioral movement in range:** 0.35.0 fixes a `preferTypedSchemaDecoder` panic on
  transformed inputs and a tagged-template TS2731 false positive, and persists Effect options in
  build info for correct incremental invalidation; 0.36.2 is diagnostic-performance-only
  (changelog states emitted diagnostics are unchanged); 0.36.3 hashes patched binaries so package
  upgrades refresh stale ones and aligns upstream fixtures to `effect@4.0.0-beta.107`; 0.36.4
  stops ignored Effect diagnostics from blocking declaration emit under `noEmitOnError`.
- **TypeScript pairing:** at 0.36.4 `typescript.latest` is still `7.0.2`, gitHead
  `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` — exactly the real-binary verifier's pin.
  `typescript.next` is `7.1.0-dev.20260808.1` (gitHead `24fabe95`); the pinned tip commit
  (`ca311a5`, "chore: update upstreams") already advances `next` metadata to
  `7.1.0-dev.20260811.1` and Oxlint to 1.78.0 without moving the `typescript-go` gitlink.
- **Still no `workspace/executeCommand`** at the new pin: `ExecuteCommand` appears only in the
  `shim/lsp` protocol shims, `_effectGetLayerMermaid` appears nowhere, and `internal/layergraph/`
  plus the hover patch are untouched across the range. Confirmed live (`executeCommands: []` in
  the 2026-08-13 verifier run). Hover Mermaid links remain the only layer-graph delivery.
- **Effect alignment:** only `9190801` (0.36.3) adapts to a newer Effect (beta.107 dependencies
  and fixtures); no completion, rename, or blocked-module logic changed, and no rc.108 reference
  exists at the tip.

### Effect-TS/effect — v4 runtime (`4c28b0fb5` → `7018f9668`, 98 commits, beta.104 → rc.108)

- **Dev Tools wire format: untouched outright.** Zero commits in the range touch
  `packages/effect/src/unstable/devtools/` or `packages/effect/src/unstable/observability/`
  (scoped `rev-list --count` is 0 for both; scoped `diff --stat` empty). No decoder change;
  protocol regression smoke suffices.
- **The beta → rc transition is procedural, not a freeze:** the rc.108 release flips the
  Changesets prerelease tag from `"beta"` to `"rc"` and the README now recommends
  `npm install effect@rc`. npm dist-tags after the release: `latest` = 3.22.1 (v3),
  `beta` = 4.0.0-beta.107, `rc` = 4.0.0-rc.108, no `next` tag, nothing published after rc.108.
  No API-freeze promise appears anywhere in the range — v4 remains prerelease.
- **Fixture-relevant breakage in range:** beta.105 restructures Schema parser error exposure
  (structured issues as `cause`, generic "Schema validation failed" messages); beta.106
  consolidates arbitrary derivation into `Schema.toArbitrary` (removing `toArbitraryLazy`);
  rc.108 removes the standalone `SchemaError` module (now `Schema.SchemaError` /
  `Schema.isSchemaError`). A repo-wide grep confirmed **none of the plugin's fixtures, test data,
  or scripts reference any of these** — no fixture changes were needed.
- **Tracer-adjacent semantics (outside the scoped dirs):** since beta.106, spans created with
  tracer timing disabled keep zero end times; log annotations can no longer overwrite active
  `spanId`/`traceId` correlation attributes. Duration rendering should tolerate zero end times;
  no wire-shape impact.
- The 16 commits between the rc.108 tag and the pinned tip are docs/tests/cluster/HTTP work with
  an empty scoped diff for both protected directories.

### effect-ts/vscode-extension (`c49b1c29` → `64631d41`, 2 commits, 0.9.0 → 0.10.0)

- **"sketch v4 support" (`19e2b1a`) is a parity signal, not a wire-schema migration.** It adds
  v4 runtime coverage to the **debugger-injected instrumentation only**: runtime shape detection
  plus hard-coded private v4 keys (`~effect/Fiber/currentFiber`, `~effect/Context`,
  `~effect/Exit`, `~effect/observability/Metric/MetricRegistryKey`), with v3 support retained via
  renamed shims. No Effect v4 dependency is pinned; the extension still builds against v3.17.3.
- The network Dev Tools server, WebSocket transport (port 34437), message shapes, and views are
  all untouched — v4 data is translated into the existing v3-era domain shapes (Summary metrics
  even fabricate `error: 0` to fit). **No JetBrains decoder change or new capability port is
  indicated.** The parts to watch are the private `~effect/*` keys and v4 metric-registry layout,
  which are the fragile edges of the sketch.

### Unchanged upstreams

`RATIU5/zed-effect-tsgo` and `JetBrains/intellij-platform-plugin-template` had zero new commits;
pins re-verified in place after a fresh fetch. `Effect-TS/language-service` moved by three
dependency-bump commits (recorded for provenance; historical-comparison clone only).

### Platform

No platform movement checked into this refresh; `platformVersion` stays at the WebStorm 2026.2.0.1
stable build `262.8665.341` pinned on 2026-08-03.

## Actions taken (this branch)

1. **Tests:** `CURRENT_TSGO_VERSION` bumped 0.33.0 → 0.36.4 in `EffectBinaryServiceTest.kt` (the
   constant is test-only; production managed resolution reads npm dist-tags dynamically and was
   verified compatible with the 0.36.4 tarballs without change). The synthetic component-manifest
   fixtures model the manifest shape with placeholder versions and remain representative — the
   real 0.36.4 manifest differs from 0.33.0 only in `typescript.next` version/gitHead values.
   Full Gradle test suite green on 2026-08-13 (`./gradlew test`, BUILD SUCCESSFUL).
2. **Real-binary verifier:** workspace dependency moved `effect@4.0.0-beta.104` →
   `effect@4.0.0-rc.108` (with a comment noting tsgo 0.36.4's own fixtures align to beta.107, so
   rc-only regressions would be upstream drift). Run live on 2026-08-13 against the published
   linux-x64 0.36.4 `artifacts/typescript/7.0.2/tsc`: **all four fixture lanes pass** — healthy
   (hover Mermaid link, 44 completion items, document/workspace symbols, `executeCommands: []`),
   failing, new-diagnostics (including `Replace with decodeSync` and `Add yield* statement`), and
   diagnostic-directives (suppress + re-enable). No assertion changes were needed: no new
   diagnostics or quick-fix titles exist in the range.
3. **No production Kotlin changes:** manifest parsing, artifact selection, health checks,
   `RULE_NAMES`, the Mermaid decoder, and the Dev Tools protocol decoder are all verified no-ops
   for this range.
4. **Docs:** `docs/reference-sources.md` (four pin updates including the reconciled
   vscode-extension row, new checked date, canary notes rewritten for 0.34.0–0.36.4 and
   beta.104→rc.108), `docs/usage.md` (smoke targets, `effect@rc` guidance, recorded 2026-08-13
   smoke), `docs/development.md` (verifier dependency line). `docs/getting-started.md` and
   `docs/troubleshooting.md` were revalidated and left unchanged — their layout wording is
   version-agnostic and still accurate at 0.36.4.

## Remaining follow-ups

- **IDE smoke session** (user-driven, per repo policy): managed LATEST end-to-end now serves
  0.36.4 (fresh cache and upgrade-over-0.33 cache), Dev Tools tracer smoke against
  `effect@4.0.0-rc.108` (zero-end-time spans with timing disabled should render sanely),
  layer-graph hover decode. `docs/parity-matrix.md` is intentionally untouched until that
  evidence exists — it is now two refreshes stale (baseline still 0.19.0/beta.97) and should be
  rewritten during that pass.
- **Release decision:** this branch leaves `pluginVersion` at 0.1.4 and adds no CHANGELOG entry —
  the first refresh with a zero production-code delta. Cut 0.1.5 (version bump + CHANGELOG + CI
  release draft, no Marketplace publish per issues #24/#25) if the evidence alignment should
  ship, or fold into the next code-bearing release.
- Dropped from the 0.1.4 follow-ups: filing the `schema.json` regeneration drift upstream —
  0.36.0 regenerated the schema and resolved it.

## Risks / testing notes

- **Watch item (new): tsgo↔effect-rc alignment.** tsgo 0.36.4 predates rc.108 (its fixtures stop
  at beta.107). The 2026-08-13 verifier proved all four lanes pass against rc.108 anyway; when
  tsgo cuts an rc-aligned release, re-verify Schema-related diagnostics/completions in particular.
- **Watch item (unchanged):** if npm `typescript@latest` moves past 7.0.2 before the next
  `@effect/tsgo` release, the workspace-gitHead match still handles it, but the verifier pin and
  recorded smoke need coordinated bumps. `typescript.next` metadata is churning every 1–3 days;
  it only matters if `next`-workspace users appear.
- **Pinned-tip risk:** the tsgo pin is 1 commit past the 0.36.4 tag and changes only upstream
  version metadata, a type-only-heritage execution-flow fix (`internal/typeparser/execution_flow.go`
  plus 7 refreshed Mermaid test baselines), and a rebased typescript-go patch; the
  `typescript-go` gitlink is unmoved, so
  no layout surprises are expected from it.
- **Effect v4 remains prerelease:** rc signals phase, not freeze — the repo makes no
  no-more-breakage promise. Continue grepping fixtures for removed/renamed Schema APIs
  (`SchemaError`, `toArbitraryLazy`, formatted-error-message assertions) before each dependency
  advance.
- **vscode-extension v4 sketch:** tracks v4 through private `~effect/*` runtime keys with no v4
  dependency pin — treat as a direction signal for debugger-side parity, not a port target, and
  expect those keys to churn.
- **Repo-move hygiene for future refreshes** still applies: never run unscoped log/diff across
  the effect-v4 transplant range; always path-scope.

## Explicit list of files that were updated

- `src/test/kotlin/dev/effect/intellij/binary/EffectBinaryServiceTest.kt` —
  `CURRENT_TSGO_VERSION` 0.33.0 → 0.36.4.
- `scripts/verify-real-tsgo-lsp.mjs` — workspace dependency `effect@4.0.0-rc.108`, smoke-target
  comment.
- `docs/reference-sources.md` — pins (tsgo, effect, vscode-extension, language-service), checked
  date, canary notes.
- `docs/usage.md` — smoke targets, `effect@rc` install guidance, recorded 2026-08-13 real-binary
  smoke.
- `docs/development.md` — verifier dependency line.
- `UPSTREAM_REFRESH_REPORT.md` — replaced with this report.

No production source changed. `docs/getting-started.md`, `docs/troubleshooting.md`,
`gradle.properties`, and `CHANGELOG.md` are intentionally untouched (the latter two pending the
release decision). `.repos/` contents changed on disk (four clones advanced, two re-verified) but
remain git-ignored and uncommitted. `docs/parity-matrix.md` intentionally untouched pending IDE
smoke evidence.
