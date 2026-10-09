# Upstream Refresh Report — 2026-10-05

Branch: `refresh/tsgo-0.48.1-effect-4.0.1`

Plugin release build: `0.1.8`

The refresh branched from `main` at `a02471f2`, after Dependabot #44 (IntelliJ Platform Gradle Plugin
2.19.0), #45 (its settings plugin 2.19.0), #46 (`jlumbroso/free-disk-space` 2.0.0), #47 (Jackson
2.22.3), #48 (Gradle wrapper 9.8.0), #49 (Kover 0.9.10), and #50 (`gradle/actions` 6.4.0) merged.
Each was green and mergeable on its own before merging. Those dependency updates are baseline work,
not ports from the IntelliJ template.

Kickoff targets were fixed on 2026-10-05 from live npm dist-tags, upstream default-branch heads, and
the JetBrains release feed, and were not moved during the run.

## Reference inventory

| Reference | Previous pin | Current recorded pin | Commits | Classification |
| --- | --- | --- | ---: | --- |
| Effect tsgo | `a7414389ab0d325b92684a2b8f50d6f0e02d6c41` | `d1e539c4956bf4b2d1643c58e1477f7597b20d5b` | 27 | Implement, validate, and document |
| Effect v4 | `ccae35423188f58d7c3dec5db3e36ed4bf42bcdf` | `b1d200c40a1dad69def51ebdbf0a1a612a12b8ac` | 262 repo-wide | Implement fixture path move; validate runtime; document |
| Effect VS Code extension | `64631d41a75770149361703581e923cf6971d5f4` | unchanged | 0 | Not applicable |
| Zed Effect tsgo extension | `0c4f302c861359b4f9d23f58ac146101030c6229` | unchanged | 0 | Not applicable |
| Effect language service | `5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4` | `690fa515f923a159638a9f78acb51a4bbccd3d36` | 3 | Not applicable |
| IntelliJ Platform Plugin Template | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | `b7b700870b23e1323353a0350a1acd9cc6d2b225` | 10 | Validation only; one item left to Dependabot |

Inventory method: all six clones were clean and detached at their previous pins. Each was fetched
with prune and tags through the documented `checkout_reference` routine and detached at the target;
`HEAD` equals the recorded revision in every clone. No `git pull`, dirty-clone reset, or access to
`.repos/effect-tsgo` was needed.

The Effect commit count is graph-wide. Analysis was restricted to the DevTools sources (across
their directory move), `Tracer.ts`, and `internal/{core,effect}.ts` for the runtime fields the
debugger reads; it is not a repository-wide Effect audit.

## Delta analysis and decisions

### Effect tsgo

The published kickoff target is `@effect/tsgo@0.48.1`, published 2026-10-05. Its release tag is the
recorded source pin, so no rule or package change is unpublished. Releases 0.46.0, 0.46.1, 0.47.0,
0.47.1, 0.47.2, 0.48.0, and 0.48.1 fall inside the delta.

- **Implement:** add five rule names to directive completion. Release metadata grows from 113 to
  118 rules: `catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and `catchRefailToTapError`
  (0.46.0; deferred as unpublished in the previous refresh), and `unstableApiUsage` /
  `experimentalApiUsage` (0.47.0). No rule was removed or renamed. A release-tag snapshot checks
  all 118 completion names.
- **Implement and validate:** pin real-binary fixtures to 0.48.1 / Effect 4.0.1, retaining
  TypeScript 7.0.2. New live cases apply the `Replace with Effect.catchTag` and
  `Replace with Effect.andThen` quick fixes, expect one rewrite-free `catchRefailToTapError`
  suggestion, and expect `unstableApiUsage` / `experimentalApiUsage` warnings naming a local
  `@stability`-tagged export as `module#exportName`.
- **Validation only:** the linux-x64 package retains schema 5, the same component/provider shape,
  executable modes, exact TypeScript git-head matching, and the stable compatibility copy. Stable
  `artifacts/typescript/7.0.2/tsc` and `lib/tsc` are byte-identical, mode `0755`, 25,858,208 bytes,
  SHA-256 `9fdb8079079036c949978bbaeef5d4230fb534d4db2b00dfdad008966428b42a`. Published next is
  `7.1.0-dev.20260929.1` at `0681ef7fa3a2378ccf49645b6d5b7463bdca74bb`; `lib/tsc-next` is absent.
- **Validation only, with a new regression test:** since 0.48.0 the compilers no longer embed the
  TypeScript standard library; `lib.*.d.ts` files ship beside each executable (107 beside each
  stable copy, 112 beside next). Managed installation extracts the entire platform package and
  launches the selected compiler in place, so the libraries arrive with it and no resolver branch
  is needed. A test now asserts the library file stays beside the selected compiler. All four live
  LSP lanes ran against the packaged stable compiler in its extracted package.
- **Document:** the `strict` preset and `unknownRuleName` configuration diagnostic (0.46.0), and the
  `allowedUnstableApis` / `allowedExperimentalApis` options (0.48.0; dual-export naming fixed in
  0.48.1), are upstream configuration behavior. The plugin's tsconfig sync writes only
  `diagnosticSeverity` and other typed options it already owns and does not manage presets.
  `diagnostics` CLI reporting of files without the plugin enabled, `effectFnOpportunity` rewrite
  fidelity, and `patch` without `--force` are CLI or server-internal changes; direct IDE
  `--lsp --stdio` launch is unchanged.
- **Document and defer IDE ownership:** the package now carries Oxlint 1.82.0–1.86.0, tsgolint
  7.0.2001 / 7.0.2003, and seven compatibility profiles. Oxlint and tsgolint remain
  upstream-managed package contents; IDE-managed configuration and lifecycle remain deferred.
- **Validate and defer bridge work:** the stable compiler advertises no `executeCommandProvider`
  commands in all four lanes, and source has no `_effectGetLayerMermaid` registration. Hover
  Mermaid remains the supported graph path.

### Effect v4

The exact runtime target is `effect@4.0.1`, published 2026-10-05, tag
`460272d30457f4697d8b8c52cad41caccbcace08`. Effect `4.0.0` was published 2026-10-01 and npm `latest`
now points at v4. The source pin is three commits past the 4.0.1 tag.

- **Implement:** commit `1b4461ec3a`, included in rc.118 and later, moves former
  `effect/unstable/*` modules to top-level paths, so DevTools is imported from `effect/devtools`.
  The runtime smoke fixture now uses that path. The instrumentation verifier rewrites its temporary
  fixture copy to the old path for releases whose `package.json` does not export `./devtools`,
  keeping the rc.112 and rc.115 controls.
- **Validation only:** compared across the directory move, `DevToolsSchema.ts`,
  `DevToolsClient.ts`, `DevToolsServer.ts`, and `DevTools.ts` differ only in relative import paths
  and documentation comments. The JSON wire shape decoded by the plugin is unchanged.
- **Validation only:** `fiber.cache.span` and `fiber.cache.stackFrame` remain the span and
  stack-frame sources at the 4.0.1 tag, and the real-runtime instrumentation lane passes there.
  No private-key migration, tracer/metrics interception, or DevTools decoder change is justified.

### Other references

- **VS Code extension — not applicable:** zero commits.
- **Zed extension — not applicable:** zero commits.
- **Language service — not applicable:** three commits (Effect rc.118 compatibility, recognizing
  Effect runners as provide entry points, and a version bump). Active rules reach the IDE through
  tsgo; nothing is ported separately.
- **IntelliJ template — validation only:** ten dependency-maintenance commits. Gradle wrapper 9.8.0,
  IntelliJ Platform Gradle Plugin 2.19.0, Kotlin 2.4.20, `setup-java` v6, and `free-disk-space`
  v2.0.0 are already on `main` through this repository's own Dependabot updates. `actions/checkout`
  v7 is not applied here and is left to Dependabot. The template's IntelliJ IDEA version bumps do
  not apply to this WebStorm-targeted build.

### Platform pins

The user's Toolbox WebStorm is 2026.3 EAP `263.6259.34`, the newest EAP at kickoff. Following the
agreed pin shape, the compile target moves to the newest stable build of the lower line, WebStorm
2026.2.3 `262.10968.77`, and the verifier targets move to WebStorm `263.6259.34` and IntelliJ IDEA
Ultimate `263.6259.32`. The `262`–`263.*` range is unchanged. No bundled-library signature break was
found on the new builds: Plugin Verifier reports no compatibility problems on any target. The
`LspServer*` → `LspClient*` API migration remains deferred to issue #26.

## Implemented change set

1. Added five directive-completion names and replaced the release snapshot with the sorted 118-name
   `rule-names-0.48.1.json`; the Kotlin test rejects duplicates, omissions, and unpublished extras.
2. Pinned LSP fixtures to Effect 4.0.1 and added the five new-rule cases, including applied
   quick-fix edits, cleared findings, and unchanged baseline errors.
3. Refreshed managed-binary test inputs to tsgo 0.48.1 and the packaged next version/git head, added
   sibling `lib.d.ts` entries to the synthetic archives, and added the library-placement test. The
   synthetic archives do not claim to contain the real package bytes.
4. Moved the runtime smoke fixture to `effect@4.0.1` and `effect/devtools`, added three editor
   examples for the new rules, and updated `SMOKE_CHECKLIST.md`.
5. Extended `scripts/verify-instrumentation.mjs` to Effect 4.0.1 with the legacy import rewrite and
   a negative control for every release after rc.112.
6. Set plugin version 0.1.8, the new stable compile target and EAP verifier pins, and
   `gradleVersion=9.8.0`.
7. Updated the six-reference provenance, canary notes, this report, changelog, README,
   usage/development guides, and one troubleshooting line. The parity matrix is unchanged pending
   user-run smoke.

No production Kotlin or injected-instrumentation logic changed beyond the completion name list.

## Verification evidence

All commands ran on 2026-10-05 against the final source tree unless noted.

### Published native binary and instrumentation

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary <extracted @effect/tsgo-linux-x64@0.48.1>/artifacts/typescript/7.0.2/tsc
node scripts/verify-instrumentation.mjs
```

Results: **exit 0** for all four LSP lanes and **exit 0** for all three instrumentation versions.

- Before any fixture edit, all four LSP lanes already passed against 0.48.1 / Effect 4.0.1 with
  only the version pins changed, so the existing coverage carries over unmodified.
- New diagnostics: the five new findings are reported; `Effect.succeedSome`, `Effect.succeedNone`,
  `Effect.forEach`, `Effect.catchTag`, and `Effect.andThen` rewrites clear their findings and
  preserve the one baseline error. `obsoleteSchemaImport` remains one warning with suppression-only
  actions. Directive lanes are unchanged and passing. No lane advertises execute commands.
- Instrumentation: Effect `4.0.0-rc.112`, `4.0.0-rc.115`, and `4.0.1` all pass with the
  inner-to-outer span stack `smoke.work > smoke.child > smoke.parent`, fixture source and defect
  locations, interruption, and the legacy/cache controls. rc.115 and 4.0.1 confirm the legacy
  fiber fields are absent.
- Runtime fixture: with `effect@4.0.1` installed, `node index.mjs` starts and logs the expected
  periodic child failures; the packaged `lib/tsc --noEmit` reports the eight intended Effect
  warnings in `example.ts` and no TypeScript errors (the process exits 1 because diagnostics were
  reported). This is runtime evidence, not IDE UI proof.

### Release command

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

Result: **BUILD SUCCESSFUL in 5m 39s**.

- Tests: **104 completed, 0 failures, 0 errors, 0 skipped**.
- `buildSearchableOptions`: 284 configurables.
- Qodana for JVM 2026.1.4: **no new problems found**.
- Plugin Verifier, 35 known deprecated JetBrains LSP API usages on every target, zero compatibility
  problems:

| Target | Verdict |
| --- | --- |
| WebStorm `WS-262.10968.77` | Compatible |
| WebStorm `WS-263.6259.34` | Compatible |
| IntelliJ IDEA Ultimate `IU-263.6259.32` | Compatible |

### Bounded sandbox boots

`./gradlew runIde` and `./gradlew runIdeVerifierWebStorm` were started under a bounded `timeout`.
Both sandbox IDEs started and logged `Loaded custom plugins: Effect TSGO (0.1.8)` — on stable
`WS-262.10968.77` and on EAP `WS-263.6259.34` — with zero Effect-attributed ERROR/SEVERE records.

Neither boot ran to its timeout. The EAP IDE exited after about seven seconds through
`WelcomeFrame … windowClosing`, meaning its welcome window was closed on the desktop; the stable
IDE shut down after about 37 seconds (a bundled Node child process had exited with code 130 at 23
seconds, which points at an external interrupt), and Gradle reported exit value 2 for that task. The SEVERE records in both logs are the platform's own shutdown-path reports (a
write-thread assertion while closing projects on the EAP, and disposer leak reports blamed on the
bundled JavaScript and Git plugins on stable). The boots were not repeated. They prove plugin load
on both builds, not a sustained idle session.

### Distribution and local install

```bash
unzip -t build/distributions/effect-jetbrains-plugin-0.1.8.zip
sha256sum build/distributions/effect-jetbrains-plugin-0.1.8.zip
```

- ZIP: `build/distributions/effect-jetbrains-plugin-0.1.8.zip`
- Size: **5,224,979 bytes**
- SHA-256: `b3de6958adcd7f992c14a49fcaa9d9715d1c159bc22fcfbdab075bc4abedbcc9`
- ZIP integrity: no errors detected in compressed data
- Nested descriptor: `dev.effect.jetbrains`, `0.1.8`, build range `262`–`263.*`
- Install state: **built, not installed** at the time of writing. The install is gated on the
  user's WebStorm being closed; no IDE process was terminated.

## Manual evidence still owed

Use the repository fixture's
[`SMOKE_CHECKLIST.md`](src/test/testData/fixtures/runtime/smoke-app/SMOKE_CHECKLIST.md) for the combined
editor/DevTools/paused-debugger pass on WebStorm `263.6259.34`. It covers install/enable, the exact
0.48.1 / 4.0.1 dependencies, diagnostics and applied fixes including the new rules, standard-library
navigation, directive completion, Layer hover, tracing, metrics, span/source snapshots, pause on
defects, and interruption.

This pass still owes the manual evidence carried from builds 0.1.2, 0.1.5, 0.1.6, and 0.1.7. Keep
`docs/parity-matrix.md` unchanged until the user reports results. The merge step remains user-gated
after smoke evidence. No Marketplace publish, GitHub release, or PR merge was performed.
