# Upstream Refresh Report — 2026-07-24

Reference-clone refresh and plugin-impact analysis for the `effect-jetbrains-plugin` upstreams.
Previous refresh: 2026-07-10 (`specs/plans/07-upstream-tsgo-0.19-effect-beta97-refresh.md`).
All clones live under git-ignored `.repos/` as plain clones with detached HEAD, per
`docs/reference-sources.md`; none of their contents are committed.

## Summary of refresh (old → new SHAs, date)

Refreshed on **2026-07-24** using the documented `checkout_reference` pattern (full fetch +
detached checkout; no `git pull`).

| Upstream | Local path | Old pin (2026-07-10) | New pin (2026-07-24) | Version movement |
| --- | --- | --- | --- | --- |
| Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `f0d48a67` | `f75c9b92` | `@effect/tsgo` **0.19.0 → 0.24.3** |
| Effect-TS/**effect** (origin repointed from `effect-smol`) | `.repos/effect-v4` | `5946da38` | `cea1d9c9` | `effect` **4.0.0-beta.97 → 4.0.0-beta.101** |
| effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29` | `c49b1c29` (re-verified) | unchanged |
| RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c` | `0c4f302c` (re-verified) | unchanged |
| Effect-TS/language-service | `.repos/effect-language-service` | — (new reference) | `f26d5835` | `@effect/language-service` 0.87.1 at pin |
| JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f574` (documented, not cloned) | `7002f574` (now actually cloned) | unchanged |

**Effect v4 repository move.** Active Effect v4 development moved from `Effect-TS/effect-smol` to
`Effect-TS/effect` `main`: merge commit `5fcc13f05` ("Merge frozen Effect V4 history into Effect")
joined the frozen v4 history into the canonical repository, and the old smol pin `5946da38` is a
verified ancestor of the new main (`git merge-base --is-ancestor` passes). beta.98 was the last
release cut from effect-smol; beta.99 was the first from `Effect-TS/effect`. The `.repos/effect-v4`
clone's origin was repointed in place via the existing helper (`remote set-url` + full fetch).
Nuance: the GitHub API still reports effect-smol as `archived: false` (last pushed 2026-07-14), so
the docs describe the development as **moved**, not archived.

## Per-upstream delta

### Effect-TS/tsgo (`f0d48a67` → `f75c9b92`, 36 commits, npm 0.19.0 → 0.24.3)

Releases in range: 0.20.0, 0.21.0, 0.22.0, 0.23.0, 0.24.0, 0.24.1, 0.24.2, 0.24.3.

**Four new diagnostics** (all flow through the existing publishDiagnostics/codeAction pipeline):

| Rule | Since | Default severity | Quick fix | Notes |
| --- | --- | --- | --- | --- |
| `missingPipeableSignature` | 0.21.0 | off (opt-in audit) | no | Exported fixed-arity functions without pipeable overloads; v3+v4 |
| `schemaOpaqueInstanceMember` | 0.22.0 | **error (on by default)** | no | Instance members in classes extending `Schema.Opaque`; **Effect v4 only** |
| `syncToSucceed` | 0.23.0 | suggestion (on by default) | yes | `Effect.sync` returning a constant → `Effect.succeed` |
| `preferSchemaTypeProperty` | 0.24.0 | off (opt-in) | yes | `Schema.Schema.Type<typeof X>` → `typeof X.Type` |

**LSP surface** (assert-from-source):

- `--lsp --stdio` launch dispatch is untouched; the only CLI change adds an
  `--effect-cli-diagnostics` branch for the new `effect-tsgo diagnostics` subcommand (0.20.0) — a
  headless diagnostics reporter with JSON/GitHub-Actions output, not needed by the LSP integration
  but a candidate for CI usage.
- **Still no `workspace/executeCommand` registered** at 0.24.3, and `_effectGetLayerMermaid`
  appears nowhere in the tree. The plugin's forward-compatible probe stays dormant.
- Hover Mermaid link contract unchanged: `mermaid.live/edit#pako:` (and `mermaidchart.com/play#`)
  fragments, base64url (no padding) of zlib-BestCompression `{"code": …}` JSON, gated on
  `noExternal=false` (`internal/layergraph/mermaidurl.go` unchanged in range).
- 0.24.2 (#373) widened `yield*` hover: the enhanced Effect quickinfo now also returns when
  hovering the asterisk/trailing space, not just the `yield` keyword.
- Diagnostic noise reductions: type-depth overflow guards (#364, #369, #389) and
  `multipleEffectProvide` now ignoring Effect v4 local `Effect.provide(..., { local: true })`
  calls (#387).
- 0.20.0 (#344) changed the **layer-magic refactor** (not hover): compositions now start from
  `Layer.empty` when the first layer doesn't provide a requested output service.

**Packaging / binary contract** (assert-from-source):

- Platform-package set unchanged (7 packages: win32-x64/arm64, linux-x64/arm64/arm,
  darwin-x64/arm64); files unchanged (`lib/tsc`, `lib/tsc-next`, adjacent `*.json` metadata with
  the same `tsVersion`/`tsGitHead` field names; `.exe`/`.exe.json` on win32). No legacy `lib/tsgo`
  binary ships at either pin.
- 0.24.3 (#391): the release pipeline now `chmod 0755`s Unix binaries before packing, so tarball
  entries carry the executable bit (before 0.24.3 they could be 0644). The plugin already restores
  POSIX permissions defensively — no code change needed.
- `lib/tsc` at 0.24.3 is still built from `typescript@7.0.2` (gitHead `2bd066d8…`), which exactly
  matches the plugin's real-binary verifier pin. `lib/tsc-next` tracks `typescript@next` 7.1.0-dev.
- The base `@effect/tsgo` package now ships `schema.json` (language-service options schema) — a
  potential future input for settings validation/completion.

**Breaking changes: none found** for the plugin's launch, settings passthrough (tsconfig
`plugins[].name` `"@effect/language-service"` unchanged), diagnostics pipeline, or binary
resolution.

### Effect-TS/effect — v4 runtime (`5946da38` → `cea1d9c9`, 90 commits touching `packages/effect`, beta.97 → beta.101)

- **Dev Tools wire format: byte-for-byte unchanged.** Scoped `git diff` over
  `packages/effect/src/unstable/devtools/` and `unstable/observability/` between the pins is empty.
  Metric snapshot shape (`{id, type, description, attributes, state}`), span encodings,
  ReadonlyMap→array encodings, and `Ended.exit` cause encoding are all identical. `parseMetric` and
  `parseSpanStatus` in `EffectDevToolsService` need no changes.
- **Instrumentation property paths all survive** at beta.101: `~effect/Fiber/currentFiber` global
  key, numeric `fiber.id`, `_children`, `interruptible`, `_interruptedCause`, `currentSpan`,
  `currentStackFrame` (`{name, stack(), parent}`), `context.mapUnsafe`, `addObserver`,
  `interruptUnsafe`, `cause.reasons` with `{_tag:'Die', defect}`. The FiberImpl fields removed in
  the range (`currentLoopCount`, `_currentExit`) are not referenced by the plugin.
- **beta.100 fiber-exit cleanup (#6495)**: after a fiber completes and observers run, `_stack` is
  truncated, `_children` becomes `undefined`, and `fiber.context` is replaced with
  `Context.empty()`. The plugin's defect/removal observers fire first, and
  `currentSpan`/`currentStackFrame` are *not* cleared, so pause-on-defect location capture keeps
  working — but Context/children snapshots of already-completed fibers now come back empty instead
  of last-known state (acceptable for best-effort semantics; verify live).
- **beta.100 deferred self-interrupt (#6484)**: `fiber.interruptUnsafe()` on a *running* fiber now
  sets `_deferredInterrupt` instead of re-entering the run loop; the interrupt takes effect on
  resume. `_interruptedCause` is still set synchronously, so the plugin's snapshot
  `isInterrupted` state is correct immediately; actual termination lands after the debugger
  resumes. The zero-arg call the plugin makes remains valid.
- **beta.101 (#6545)**: interruptor stack frames are now annotated under the new
  `effect/Cause/InterruptorStackTrace` key instead of `CauseStackTrace`. The instrumentation does
  not read cause annotations (it only scans `cause.reasons` for `Die.defect`) — no impact, noted
  for any future annotation parsing.
- Other beta.98–beta.101 changes (Clock.sleep long-duration chaining, CLI wizard mode,
  `Effect.reduce`, `ManagedRuntime` `Symbol.asyncDispose`, prototype-pollution guards in record
  assignment) have no plugin coupling. Plugin-relevant files with **zero** commits in range
  (verified per-file): `Tracer.ts`, `internal/tracer.ts`, `Fiber.ts`, `Context.ts`, `Cause.ts`,
  `Exit.ts`, `internal/metric.ts`, `References.ts`, `Redactable.ts`, `Layer.ts`, `internal/layer.ts`.

### effect-ts/vscode-extension (`c49b1c29`, unchanged)

Zero delta: HEAD equals the pin and `git log <pin>..origin/main` is empty after a fresh fetch. The
Dev Tools / metrics / tracer / debugger reference surface is exactly what the plugin was already
built against.

### RATIU5/zed-effect-tsgo (`0c4f302c`, unchanged)

Zero delta, same verification. The native-launch and settings-model reference is unchanged.

### Effect-TS/language-service (`f26d5835`, new reference)

The classic TypeScript-server (tsserver) plugin for Effect, published as
`@effect/language-service` (0.87.1 at the pin), still actively developed (pin is a release commit
dated 2026-07-23) and dual-tested against Effect v3 and v4. Its README directs TypeScript 7+ users
to `@effect/tsgo`. tsgo does **not** vendor or depend on this package — tsgo is a Go-native
reimplementation of the same experience that honors the identical tsconfig `plugins[]` entry name.
Value as a reference: it is where new rules typically land before being ported to tsgo, and it
carries the canonical per-rule v3/v4 support and severity catalog. Documented in
`docs/reference-sources.md` as optional historical/TS-plugin comparison only.

### JetBrains/intellij-platform-plugin-template (`7002f574`, now cloned)

Unchanged upstream (last pushed 2026-05-04); the previously documented pin is now an actual clone.
Notable for later phases: at this commit the template's Gradle config is radically slimmed
(17-line `build.gradle.kts` relying on IntelliJ Platform Gradle Plugin 2.16.0 conventions +
`org.jetbrains.intellij.platform.settings`), and its release-draft/publishing workflows
(`getChangelog --unreleased`, `publishPlugin` with the four canonical secrets) are the natural
comparison for this plugin's CI — relevant when triaging the open `org.jetbrains.intellij.platform*`
2.16.0 → 2.18.1 Dependabot PRs.

## Impact on current plugin capabilities (parity gaps vs docs/parity-matrix.md)

The parity matrix (last checked against `@effect/tsgo@0.19.0` + `effect@4.0.0-beta.97`) was
deliberately **not** edited: its rows are evidence-backed at those versions and Phase 1 produced no
new test evidence. Gaps against the new pins:

| Parity-matrix row | Gap at new pins | Plugin file(s) | Evidence class |
| --- | --- | --- | --- |
| Diagnostics/code actions/completion (directive completion) | `RULE_NAMES` (83 entries, a 0.19.0 snapshot) is missing the four new rules — `@effect-diagnostics` directive completion won't offer them | `lsp/EffectDiagnosticDirectiveCompletionContributor.kt:83` | assert-from-source |
| Managed npm install / platform resolution | Contract unchanged; managed default should move to 0.24.3. #391 exec-bit guarantee is additive (plugin already restores perms). Test fixture constant `CURRENT_TSGO_VERSION = "0.19.0"` encodes the old release | `binary/EffectBinaryService.kt`, `binary/EffectBinaryServiceTest.kt:27` | assert-from-source |
| Diagnostics rendering | Volume/content shifts: `schemaOpaqueInstanceMember` on-by-default at **error** for v4 projects; `syncToSucceed` at suggestion; fewer spurious TS2589s; `multipleEffectProvide` quieter on v4 local provides | `lsp/EffectLspDiagnosticsSupport.kt` | needs-live-reverify |
| Hover Mermaid links / local preview | No gap — link contract unchanged; execute-command probe correctly dormant (still nothing to call) | `lsp/EffectLayerMermaidService.kt`, `EffectShowLayerMermaidAction.kt` | assert-from-source |
| Runtime Dev Tools (clients/metrics/tracer) | No gap — wire schema identical beta.97 → beta.101; decoders unchanged | `devtools/EffectDevToolsService.kt` | assert-from-source (protocol smoke still advised) |
| Debug Fibers view — interrupt action | Behavior shift, not breakage: interrupt of a running fiber is now deferred to resume; snapshot `isInterrupted` still immediate | `debug/`, `instrumentation.global.js` | needs-live-reverify |
| Debug Context / Span Stack views | Completed fibers now report empty Context/children (beta.100 exit cleanup); live-fiber snapshots unaffected; pause-on-defect location capture unaffected | `debug/EffectDebugBridgeService.kt`, `instrumentation.global.js` | needs-live-reverify |
| Real-binary verifier evidence | Verifier pins (`typescript@7.0.2` + `effect@4.0.0-beta.97`, assertions worded against 0.19.0) are stale as *evidence*, though `typescript@7.0.2` remains the correct stable pairing at 0.24.3 | `scripts/verify-real-tsgo-lsp.mjs:563,766` | needs-live-reverify |

## Prioritized recommended actions

**P1 — correctness / user-visible on the next bump (small, well-scoped):**

1. Add the four new rule names to `RULE_NAMES` in
   `EffectDiagnosticDirectiveCompletionContributor.kt` (spellings verified from
   `internal/rules/*.go` and `_packages/tsgo/src/metadata.json`).
2. Bump the managed `@effect/tsgo` target to 0.24.3: `EffectBinaryServiceTest.CURRENT_TSGO_VERSION`,
   verifier pins in `scripts/verify-real-tsgo-lsp.mjs` (bump `effect` to 4.0.0-beta.101;
   `typescript@7.0.2` stays), and the doc references in `docs/usage.md` / `docs/development.md` /
   `CHANGELOG.md`.
3. Re-run the real-binary verifier against `@effect/tsgo@0.24.3` end-to-end (tarball layout, exec
   bits, `--lsp --stdio` handshake, diagnostics incl. one new-rule assertion, hover/Mermaid,
   directive fixtures) and record the evidence per the parity-matrix checklist; then update
   `docs/parity-matrix.md`'s header versions.

**P2 — behavior verification (live smoke, no code expected):**

4. Dev Tools protocol smoke against an `effect@4.0.0-beta.101` app (clients, metrics, span
   `Ended.exit` causes) — source-identical schema still deserves one live pass.
5. Debugger smoke on beta.101: interrupt-fiber action (expect immediate `isInterrupted`, termination
   after resume), Context/Span Stack panels for a live paused fiber, pause-on-defect.

**P3 — quality-of-life / opportunistic:**

6. Consider surfacing the four new diagnostics in docs/usage examples, and decide whether
   `schemaOpaqueInstanceMember` (error-by-default, v4-only) deserves a troubleshooting note.
7. Optional: wire the newly published `@effect/tsgo` `schema.json` into settings/tsconfig-sync
   validation or completion.
8. Optional: evaluate `effect-tsgo diagnostics` (JSON output) as a headless/CI check.
9. Reconcile the `.repos/effect-tsgo` doc/reality mismatch: `docs/reference-sources.md` describes it
   as a locally-built tsgo working tree (MANUAL-mode target), but the directory currently contains a
   clone of the plugin's own repository (`kriegcloud/effect-jetbrains-plugin` @ `ecc92d67`). Either
   rebuild it as a real tsgo tree or drop/reword the paragraph.

Platform/dependency bumps (Dependabot PRs #21/#19/#18/#15/#14, branch protection) are Phase 2 scope;
note that the four older PRs' Windows-test/Qodana failures predate main's `cc01d3c8` CI fix and
likely clear on rebase.

## Risks / testing notes

- **Evidence classes:** every "unchanged/no change needed" claim above is asserted from pinned
  source; end-to-end behavior (npm tarball contents, LSP handshake, live protocol, debugger
  semantics) still requires the P1.3/P2 smokes before any parity or CHANGELOG claim is updated.
  One specific caveat: the "lib/tsc = typescript@7.0.2" claim reads the `generated/latest` branch
  snapshot regenerated one day *after* the 0.24.3 release — confirm gitHead match on the first live
  verifier run.
- **`schemaOpaqueInstanceMember` is error-severity by default on v4 projects** — users bumping the
  managed binary may see new red diagnostics without any plugin change; worth a release-note line.
- **Watch item:** if npm `typescript@latest` moves past 7.0.2 before the next `@effect/tsgo`
  release, future `lib/tsc` builds may stop matching workspaces pinned to 7.0.2; managed-mode
  metadata matching handles it, but the verifier pin will need coordinated bumps.
- **Repo-move hygiene for future refreshes:** never run unscoped log/diff across the effect-v4
  range (the transplant makes the raw compare ~7,700 commits; only 90 touch `packages/effect`).
  Always path-scope, as now noted implicitly by the canary notes. beta.98's changelog links still
  point at effect-smol PRs; beta.99+ point at Effect-TS/effect — a useful transplant marker.
- **Partial-clone cost:** the reference clones are `--filter=blob:none`; directory-level batched
  diffs are cheap, per-file loops are slow. No helper deviation was needed during this refresh
  (the repoint completed via the documented path).
- The debugger behavior shifts (deferred interrupt, empty Context on completed fibers) are
  *semantics* changes inside best-effort surfaces — treat as expected-behavior updates to verify,
  not regressions to fix.

## Explicit list of files that were updated

- `docs/reference-sources.md` — modified: table (six rows, new date 2026-07-24, effect-v4 URL
  repoint + note, language-service row added, template row now cloned), refresh helper extended to
  six `checkout_reference` calls (function body unchanged), "four → six revision arguments" prose,
  and Canary Notes rewritten for `@effect/tsgo@0.24.3` / `effect@4.0.0-beta.101`.
- `UPSTREAM_REFRESH_REPORT.md` — created (this report).

`.repos/` contents changed on disk (five clones refreshed/repointed, two new clones) but remain
git-ignored and uncommitted. `docs/parity-matrix.md` was intentionally left untouched (see Impact
section). No source, test, build, or spec files were modified.
