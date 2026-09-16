# Upstream Refresh Report — 2026-09-16

Branch: `refresh/tsgo-0.45.0-effect-rc115`

Plugin release build: `0.1.7`

The refresh branched from `main` after Dependabot #36 (Jackson 2.22.2), #37 (Gradle wrapper 9.7.1),
#39 (`actions/setup-java` 6), and #41 (Qodana 2026.2.1) merged, at `305694e5`. Kotlin #42 is also
merged: `git log origin/main` records `f715c481702712b50ed1abaf7a238f77c97d9790` for Kotlin 2.4.20.
The refresh branch includes that update through merge `ace866a5b59012ca68c3706e3fc89663f357d02c`.
Those dependency updates are baseline work, not new ports from the unchanged IntelliJ template.

Scope follows [plan 08](specs/plans/08-upstream-tsgo-0.45-effect-rc115-refresh.md). Source, tests,
scripts, fixtures, and build changes were already present and uncommitted when the coupled
documentation pass began. This report uses the supplied `upstream-inventory.md` and
`impl-evidence.md` receipts. The latter ends before the lsp4j compatibility fix now in the working
tree: its earlier verifier failures and provisional ZIP are recorded below, not promoted to final
post-fix proof. No commit or reference-checkout operation was performed by the documentation pass.

## Reference inventory

| Reference | Previous pin | Current recorded pin | Commits | Classification |
| --- | --- | --- | ---: | --- |
| Effect tsgo | `1ab43807aa20595e83df4a8c5a73b7e8ae7e2e3d` | `a7414389ab0d325b92684a2b8f50d6f0e02d6c41` | 56 | Implement, validate, document, and defer unpublished rules |
| Effect v4 | `e72b12fc305710550bc6dcb978e92de8abff88cd` | `ccae35423188f58d7c3dec5db3e36ed4bf42bcdf` | 394 repo-wide / 27 path-scoped | Implement debugger compatibility; validate runtime; document |
| Effect VS Code extension | `64631d41a75770149361703581e923cf6971d5f4` | unchanged | 0 | Not applicable |
| Zed Effect tsgo extension | `0c4f302c861359b4f9d23f58ac146101030c6229` | unchanged | 0 | Not applicable |
| Effect language service | `5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4` | unchanged | 0 | Not applicable |
| IntelliJ Platform Plugin Template | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | unchanged | 0 | Not applicable |

Inventory method: all six clean, detached clones fetched `origin` with prune and tags; the inventory
retained their old HEADs and recorded the fetched `origin/main` targets. All old pins are ancestors
of their targets. This report records those reviewed revisions and the corresponding checkout
arguments in [reference sources](docs/reference-sources.md); it does not claim the local clones
were moved. No `git pull`, dirty-clone reset, or access to `.repos/effect-tsgo` was needed.

Effect logs and diffs were restricted to DevTools, observability, Fiber, Context, Tracer, Metric,
References, Redactable, and `internal/{core,effect,tracer,metric}.ts`. Only commit counts and tag
reachability were graph-wide; the 394-commit count is not a claim of a repository-wide Effect audit.

## Delta analysis and decisions

### Effect tsgo

The published kickoff target is `@effect/tsgo@0.45.0`, published 2026-09-10, with Git tag commit
`54bbc1e7f0ffe7bb555642312168a88741667c6f`. The recorded source pin is seven commits ahead.

- **Implement:** add 14 newly published and four formerly deferred rule names to directive
  completion. Source rules increase from 99 to 116; the release contains 113. No rule was removed
  or renamed, and no existing default severity changed. The previously deferred
  `allOfMapToForEach`, `mapSomeToAsSome`, `catchDieToOrDie`, and `catchConditionalRefailToCatchIf`
  are now published. A release-tag snapshot checks all 113 completion names.
- **Implement and validate:** pin real-binary fixtures to 0.45.0 / Effect rc.115, retaining
  TypeScript 7.0.2. New live cases cover `obsoleteSchemaImport` with suppression-only actions,
  applied `preferSucceedSomeOrNone` / `allOfMapToForEach` rewrites, and default-off `schemaSync`
  enabled by a next-line directive. Upstream flow/parser/refactor changes are consumed through
  normal LSP code actions, not a new IDE rewrite engine.
- **Validation only:** the linux-x64 package retains schema 5, the same component/provider shape,
  executable modes, exact TypeScript git-head matching, and stable compatibility copy. Stable
  `artifacts/typescript/7.0.2/tsc` and `lib/tsc` are byte-identical, mode `0755`, 30,523,554 bytes,
  SHA-256 `38c289dac0b72e52c8f8a877e4bee7be8b37ddcc8c02f20c141b570c7be0fe08`. Published next is
  `7.1.0-dev.20260909.1` at `f3b04fe05642d53b4ff126a4af05fe2587b43748`; `lib/tsc-next` is absent.
  No new manifest schema/resolver branch or permission fallback is applicable.
- **Document:** upstream `diagnostics --list-files` adds per-file `file`, `detectedEffect`, and
  `supportedEffect` metadata. `genericEffectServices` is v3-only, `schemaSyncInEffect` supports
  v3 + v4, and `unsafeEffectTypeAssertion` moves to `correctness` (including Oxlint presets).
  `schemaSync` defaults off, while the upstream effect-native preset enables it at warning.
  Schema/Crypto diagnostic corrections and provider/Nix/cache setup changes remain upstream
  behavior; direct IDE `--lsp --stdio` launch is unchanged.
- **Document and defer IDE ownership:** the inspected package includes Oxlint 1.81.0 / 1.82.0 and
  tsgolint 7.0.2001; expanded retention and the Vite+ 0.3.2 alias exist only in post-tag source.
  Oxlint and tsgolint remain upstream-managed package contents. IDE-managed configuration and
  process lifecycle remain deferred.
- **Defer until published:** `catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and
  `catchRefailToTapError` are absent from 0.45.0 and excluded from completion and binary fixtures.
- **Validate and defer bridge work:** both packaged compilers omit `executeCommandProvider` in
  the inventory's initialization probe; source has no `_effectGetLayerMermaid` registration.
  Stable and next differ in source-action kind suffixes. Those inventory probes exited 1 with
  `context canceled` during shutdown and prove capabilities only. The passing full fixture run
  below uses stable; hover Mermaid remains the supported graph path.

### Effect v4

The exact runtime target is `effect@4.0.0-rc.115`, published 2026-09-11, tag
`4a05d4914fa2327a42bd75fe77c22c188becf3b4`. The source pin is 31 commits past that tag.

- **Implement:** commit `9956f0e6f47096a6e31305ec2c7320b8e1a140af`, included in rc.113+, removes
  `fiber.currentSpan` / `fiber.currentStackFrame` in favor of `fiber.cache.span` /
  `fiber.cache.stackFrame`. Cache-first accessors with older-field fallback restore span stacks,
  source locations, and pause-on-defect reveal. Add real-runtime instrumentation checks and a
  shared fixture app; human paused-session validation remains owed.
- **Validation only:** the DevTools wire schema is unchanged between v4 release candidates 112
  and 115. `DevToolsSchema.ts`, `DevTools.ts`, and `DevToolsServer.ts` have no scoped delta.
  `DevToolsClient` makes explicit span snapshots and copies attributes for lazy span properties.
  Runtime probes validate the existing protocol and debugger reads.
- **Document:** metric registry keys become `effect/Metric/MetricRegistry` and
  `effect/Metric/FiberRuntimeMetrics`; the plugin uses neither old nor new literal. Per-registry
  metric-hook caching, canonical attribute ordering, negative OTLP deltas, exporter disposal,
  and optional stack-capture changes remain runtime concerns.
- **Not applicable:** current-fiber and Context private keys are unchanged. No private-key
  migration, DevTools decoder port, duplicate tracer/metrics interception, or OTLP export layer
  is justified by this delta.

### Unchanged references

- **VS Code extension — not applicable:** zero commits; no new debugger injection or DevTools UX
  port. The fiber-cache fix is justified by Effect runtime evidence itself.
- **Zed extension — not applicable:** zero commits; no native launch, discovery, configuration,
  or lifecycle delta.
- **Language service — not applicable:** zero commits; no legacy diagnostic port or separate
  package install. Active Effect language-service rules are embedded in tsgo.
- **IntelliJ template — not applicable:** zero commits; no new Gradle, signing, Qodana, verifier,
  or publication scaffolding port. Dependency PRs above are separate baseline maintenance.

### Platform compatibility finding

The user reported that 0.1.6 on WebStorm 2026.3 EAP `263.4732.34` threw
`java.lang.NoSuchMethodError: 'java.lang.String org.eclipse.lsp4j.Diagnostic.getMessage()'` from
`EffectDiagnosticDirectives.kt` via `EffectLspDiagnosticsSupport.createAnnotation`, stopping
diagnostic highlighting.

That EAP bundles lsp4j `1.0.0.v20260209-1721` in the renamed
`intellij.libraries.eclipse.lsp4j.jar`; `getMessage()` returns `Either<String, MarkupContent>`.
The 262 stable compile target and the previous 263 EAP verifier target bundle lsp4j 0.24.0, where
it returns `String`. Compiling a direct call against 262 embedded the old JVM method signature.
The supplied implementation receipt confirms two unresolved `getMessage(): String` calls on each
new EAP target, in rule-name extraction and diagnostic copying.

The working tree now routes message read/copy through `EffectLsp4jDiagnosticMessage`, resolving
getters/setters reflectively and accepting `String` or Either with a string left / `MarkupContent`
right. Unit cases cover both shapes and severity-override copying. The platform comparison supplied
with the bug report identifies only `Diagnostic.getMessage/setMessage` as changed signatures among
the lsp4j classes used by the plugin; `ServerCapabilities` gained unused inline-completion and
text-document members. EAP verifier pins must track the newest build because bundled libraries can
change within a platform line. **Post-fix test and verifier results are not present in the supplied
`impl-evidence.md`; the results below precede this adapter.**

Advance the verifier targets to WS `263.4732.34` / IU `263.4732.28`, retain stable compilation at
`262.10315.144` and the `262`–`263.*` range, and reconcile `gradleVersion` to the 9.7.1 wrapper.
The `LspServer*` → `LspClient*` API migration remains deferred to issue #26. The plan addendum also
records sandbox `RunIdeTask` isolation of `XDG_CONFIG_HOME` under `build/sandbox-xdg/config`, avoiding
host Plasma write warnings from the platform's `plasmashell --version` probe.

## Implemented change set

1. Added cache-first fiber span/stack-frame accessors with legacy fallback throughout the injected
   debugger instrumentation.
2. Added exactly 18 published directive-completion names and a sorted 113-name release snapshot,
   with a Kotlin test rejecting duplicates, omissions, and unpublished extras.
3. Pinned LSP fixtures to Effect rc.115 and refreshed managed-binary test inputs to tsgo 0.45.0,
   TypeScript 7.0.2, and the packaged next version/git heads. Synthetic archives retain computed
   size/integrity checks; they do not claim to contain the real package bytes.
4. Added live `obsoleteSchemaImport`, Some/None, all-of-map, and next-line `schemaSync` cases,
   including applied quick-fix edits, cleared findings, and unchanged baseline errors.
5. Added `scripts/verify-instrumentation.mjs` with real process-isolated runtime checks, cache and
   legacy controls, source/defect locations, interruption, exact-version overrides, and failure exits.
6. Added the repository runtime `smoke-app` fixture with exact dependencies, DevTools connection,
   nested spans, metrics, periodic typed failures, a defect loop, an interruptible never fiber,
   editor examples, and `SMOKE_CHECKLIST.md`. Ignored only its dependency/build output directories.
7. Set plugin version 0.1.7, the new EAP verifier pins, and `gradleVersion=9.7.1`.
8. Added the lsp4j reflection accessor, its directive read/copy integration and unit cases, and
   sandbox XDG configuration isolation. These later changes are evidenced by the working tree,
   user bug report, and plan addendum; their final execution receipts are still missing here.
   The current binary-test fixture also uses `127.0.2.1` for its synthetic registry.
9. Updated the six-reference provenance, refresh report, changelog, README, usage/development
   guides, and lsp4j troubleshooting. The parity matrix remains unchanged pending user-run smoke.

## Verification evidence

Implementation-run receipts (Codex lane, 2026-09-16 00:01–00:25 UTC-5) established the focused,
real-binary, instrumentation, wire-probe, and bounded-boot results below. The release command was
then rerun on the final tree, after the lsp4j accessor, the sandbox XDG isolation, the 0.1.7
changelog entry, and the test fixes landed. Sandbox lock files left behind by the bounded boots
(`config/.lock`, `system/.port`) had to be deleted before the headless searchable-options IDE could
start; see `docs/development.md`.

### Focused tests

`./gradlew test --tests dev.effect.intellij.lsp.EffectDiagnosticDirectivesTest --tests dev.effect.intellij.lsp.EffectDiagnosticDirectiveCompletionTest`
first ran with the snapshot test failing on the pre-edit list (40 tests, one failure), proving the
missing-name regression, then passed once the 18 names were added. The three lsp4j accessor cases
(plain `String`, `Either` left, `Either` right with `MarkupContent`, plus the copy round-trip) are
part of the full suite below.

### Published native binary and instrumentation

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary <extracted @effect/tsgo-linux-x64@0.45.0>/artifacts/typescript/7.0.2/tsc
node scripts/verify-instrumentation.mjs
```

Results: **exit 0** for all four LSP lanes and **exit 0** for both instrumentation versions.

- Healthy workspace: Mermaid hover link, 44 completions, expected symbols, and no advertised execute
  commands. Failing workspace: diagnostic 377008 (`missingStarInYieldEffectGen`).
- New diagnostics: one `obsoleteSchemaImport` warning with suppression-only actions; applied
  `Effect.succeedSome`, `Effect.succeedNone`, and `Effect.forEach` rewrites clear their findings and
  preserve the one baseline error. Directive opt-in reports `schemaSync` only on the enabled line,
  with suppression/re-enable cases for `strictEffectProvide` and `floatingEffect` passing.
- Instrumentation: Effect `4.0.0-rc.112` and `4.0.0-rc.115` both pass with the inner-to-outer span
  stack `smoke.work > smoke.child > smoke.parent`, fixture source and defect locations, interruption,
  and the legacy/cache controls. The rc.115 negative control confirms `fiber.currentSpan` and
  `fiber.currentStackFrame` are absent, so the cache-first accessor is what restores the data.
- Runtime fixture typecheck (`tsc --noEmit`) exit 0; 18-second DevTools wire probe exit 0 with 109 span
  messages, all four expected span names, 17 metric snapshots, periodic typed failures, and
  positive/negative gauge values. Runtime/wire evidence, not IDE UI proof.

### Release command

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

Result: **BUILD SUCCESSFUL in 2m 56s** on the final tree.

- Tests: **103 completed, 0 failures, 0 errors**.
- `buildSearchableOptions`: 284 configurables.
- Qodana for JVM 2026.1.4: **no problems found**.
- Plugin Verifier, 35 known deprecated JetBrains LSP API usages on every target, zero compatibility
  problems:

| Target | Verdict |
| --- | --- |
| WebStorm `WS-262.10315.144` | Compatible |
| WebStorm `WS-263.4732.34` | Compatible |
| IntelliJ IDEA Ultimate `IU-263.4732.28` | Compatible |

Before the lsp4j accessor, the two EAP targets each reported two compatibility problems: unresolved
`Diagnostic.getMessage(): String` in rule-name extraction and in the diagnostic copy. That is the
same failure the user hit on 0.1.6 (`NoSuchMethodError`), now caught by the advanced verifier pins.

### Bounded sandbox boots

`timeout --signal=INT --kill-after=10s 90s ./gradlew runIde` and the same for
`runIdeVerifierWebStorm` both exited **124** (expected bounded timeout). Logs show Effect TSGO 0.1.7
loaded on stable `WS-262.10315.144` and EAP `WS-263.4732.34` with plugin ID `dev.effect.jetbrains`
and zero Effect-attributed ERROR/SEVERE records. Unrelated Station Unix-socket and dconf warnings
came from the restricted runtime directory. These boots predate the sandbox XDG isolation, which
only changes the child processes' `XDG_CONFIG_HOME`.

### Distribution and local install

```bash
unzip -t build/distributions/effect-jetbrains-plugin-0.1.7.zip
sha256sum build/distributions/effect-jetbrains-plugin-0.1.7.zip
```

- ZIP: `build/distributions/effect-jetbrains-plugin-0.1.7.zip`
- Size: **5,223,239 bytes**
- SHA-256: `bdff4862b8acd790333b14e650f4f3d5d69fd541349f9ec7240f5080944824d5`
- ZIP integrity: no compressed-data errors
- Nested descriptor: `dev.effect.jetbrains`, `0.1.7`, build range `262`–`263.*`
- Install state: **built, not installed**. WebStorm was running when the artifact was ready; the
  install is the user-gated step and no IDE process was terminated.

## Manual evidence still owed

Use the repository fixture's
[`SMOKE_CHECKLIST.md`](src/test/testData/fixtures/runtime/smoke-app/SMOKE_CHECKLIST.md) for the combined
editor/DevTools/paused-debugger pass on WebStorm `263.4732.34`. It covers install/enable, the exact
0.45.0 / rc.115 dependencies, diagnostics and applied fixes, directive completion, Layer hover,
tracing, metrics including negative values, span/source snapshots, pause on defects, and interruption.
The install step waits for the user to close WebStorm; no user IDE process was terminated.

This pass still owes the manual evidence carried from builds 0.1.2, 0.1.5, and 0.1.6. Keep
`docs/parity-matrix.md` unchanged until the user reports results. The later merge step remains
user-gated after smoke evidence and its parity update. No Marketplace publish, GitHub release,
PR merge, or commit was performed by this documentation update.
