# Upstream Refresh Report — 2026-11-06

Branch: `refresh/tsgo-0.67.4-effect-4.1.2`

Plugin release build: `0.1.10`

Refresh targets were fixed on 2026-11-06 at `@effect/tsgo@0.67.4`, `effect@4.1.2`, and the
JetBrains stable/GA builds below. Only the tsgo and Effect reference pins moved. Implemented claims
follow the 0.1.10 changelog; the release and installation gates remain separate from native-binary
and runtime evidence.

## Reference inventory

| Reference | Previous pin | Current recorded pin | Commits | Classification |
| --- | --- | --- | ---: | --- |
| Effect tsgo | `018720b8553d9198c5d69b060175ef663e3baa10` | `bf34a20a49154ceca6ac58b653f36aa4ff405e47` | 84 | Implement, validate, and document |
| Effect v4 | `1b740862e3d25ec5d4b6f86b9fe792b6dabbf84c` | `506eef143bcc1750ce58920aac1e45da7265d103` | Path-scoped analysis | Validate runtime; move fixture pins; document |
| Effect VS Code extension | `64631d41a75770149361703581e923cf6971d5f4` | unchanged | 0 | Not applicable |
| Zed Effect tsgo extension | `0c4f302c861359b4f9d23f58ac146101030c6229` | unchanged | 0 | Not applicable |
| Effect language service | `cb91d700a10e86de46d57b94261d4dcc9c92fc20` | unchanged | 0 | Not applicable |
| IntelliJ Platform Plugin Template | `c7c9f997a7e67920efc534202e7ec29684d3b161` | unchanged | 0 | Not applicable |

The tsgo pin equals tag `@effect/tsgo@0.67.4`; the Effect pin equals tag `effect@4.1.2`. The other
four pins equal their upstream HEAD at the check. The previous Effect pin was untagged `main`
past the published 4.0.5 release. Effect analysis was restricted to DevTools, tracer, metrics,
fiber, context, and fixture-facing APIs; it is not a repository-wide Effect audit. The recorded pins
and detached-checkout routine are in `docs/reference-sources.md`.

## Delta analysis and decisions

### Effect tsgo

The published target and source pin now coincide at 0.67.4. The source-pin delta adds 17 rules;
the plugin's published baseline grows from 129 to 147 because `preferEffectString` was already on
the old source pin but unpublished at 0.54.1.

- **Implement:** add 18 published names to directive completion and replace the release snapshot
  with all 147 names. The default-on suggestions with rewrites are `effectSucceedWithUndefined`,
  `failCauseToConstructor`, `flatMapIdentityToFlatten`, `manualMetricTapToTrack`, `mapConstToAs`,
  `mapSomeCatchNoSuchElement`, `matchBooleanToIsSuccess`, `matchToResultOrOption`,
  `resultMatchToFromResult`, and `suspendFailToFailSync`. The off-by-default additions are
  `effectTryWithoutCatch`, `globalJson`, `globalJsonInEffect`, `mapMapErrorToMapBoth`,
  `provideMergeAllToArray`, `typeUnionToSchemaUnion`, `yieldInCallArgument`, and
  `preferEffectString`. No rule was removed or renamed.
- **Implement and validate:** pin real-binary fixtures to 0.67.4 / Effect 4.1.2, retaining
  TypeScript 7.0.2. New fixture cases apply `Replace with Effect.as` (`mapConstToAs`) and
  `Replace with Effect.flatten` (`flatMapIdentityToFlatten`).
- **Validation only, with regression tests:** the schema-5 manifest adds TypeScript `beta`
  (`7.1.0-beta`, git head `3e9236361fa97a9e6c685f4833ac457ae2d8c50a`) and a `tsrs` component.
  Stable TypeScript 7.0.2 still ships as `lib/tsc`; beta and next use
  `artifacts/typescript/<version>/tsc`. Exact-git-head matching already selects beta for a matching
  workspace and ignores Rust and lint components, so no production resolver change is needed.
- **Document:** `keyPatterns.target: "taggedClass"` and `brandedSchemaClasses` support per-file
  overrides. Branding defaults to `Class`, `TaggedClass`, `Error`, `TaggedError`, and `Opaque`;
  `[]` disables it, including completion insertion. `preferSchemaOverJson` remains functional but
  deprecated; configuring it reports warning `deprecatedRuleName`. Use `globalJson` /
  `globalJsonInEffect`. This configuration diagnostic is separate from the 147 source-rule count.
- **Validate and defer managed selection:** the Effect-patched tsrs executable is the Rust
  TypeScript 7.1 port from `oxc-project/tsrs`, version `0.22.0-ts7.1.0-beta`. It serves Effect LSP
  over `--lsp --stdio` and passes all five verifier lanes, but interactive IDE qualification remains
  owed. Use `MANUAL` mode for an opt-in trial; managed selection continues to choose TypeScript
  components only.
- **Document and defer IDE ownership:** the manifest carries Oxlint 1.88.0–1.91.0, tsgolint
  7.0.2004–7.0.2006, and seven compatibility profiles, with Vite+ 1.3.0–1.5.0. CLI patch/proxy and
  lint-worker choices do not change the plugin's direct LSP launch. Oxlint execution stays with
  the official Oxc plugin and repository tooling.
- **Validate and defer bridge work:** initialize advertises inlay hints, semantic tokens, and pull
  diagnostics, with no `executeCommandProvider`. Layer Mermaid remains encoded-hover transport.

### Effect v4

The fixtures move from `effect@4.0.5` to `effect@4.1.2`. The source comparison starts at the old
untagged pin, so initial Ping, reconnect, and dynamic metric registration/removal already present
there are not new ports.

- **Validation only:** `DevToolsSchema.ts` and the wire shapes are unchanged. The private
  current-fiber and Context keys, live `fiber.cache.span` / `fiber.cache.stackFrame` reads, and
  metric state shapes remain compatible. No decoder or injected-instrumentation change is required.
- **Validate and document:** completed fibers clear cached span and stack-frame references after
  completion observers run. The plugin captures defect locations during those observers. Ended
  spans ignore late mutations and events; DevTools suppresses duplicate end snapshots. Focused
  4.1.2 probes confirm observer-time visibility, cleared completed caches, supplied Context service
  values, and ignored late span mutations.
- **Document:** 4.1.2 SQL tracing emits transaction child spans rather than commit/savepoint/rollback
  events; the generic span renderer accepts these shapes. OTLP, RPC, crypto, and unrelated Schema
  changes do not require plugin changes.

### Other references

- **VS Code, Zed, language service, and IntelliJ template:** unchanged; no separate port.
- **JetBrains builds:** compile against WebStorm 2026.2.4 `262.11879.105`; target Plugin Verifier
  at WebStorm 2026.3 GA `263.7307.202` and IntelliJ IDEA Ultimate 2026.3 GA `263.7307.200`.
  WebStorm 2026.3 GA was released 2026-11-05. Retain the `262`–`263.*` compatibility range.
  Earlier EAP verdicts do not establish GA compatibility.
- **Platform APIs:** the `LspServer*` → `LspClient*` migration is deprecated since 2026.1.4 but
  remains functional on 263. Keep issue #26 separate from this refresh. The 263 debugger module
  dependency check and interactive editor/debugger validation remain follow-up work.
- **Oxc ownership:** the inspected official Oxc plugin build declares `253`–`262`, excluding 263.
  Do not promise 263 coexistence from that build or add a second Oxlint process manager to Effect.

## Implemented change set

1. Added 18 published directive-completion names and replaced the release snapshot with the
   147-name `rule-names-0.67.4.json`.
2. Pinned LSP fixtures to tsgo 0.67.4 / Effect 4.1.2, retaining TypeScript 7.0.2, and added applied
   `mapConstToAs` / `flatMapIdentityToFlatten` quick-fix cases.
3. Added managed-binary regression inputs for the beta TypeScript and tsrs manifest components;
   production selection remains unchanged.
4. Moved the runtime smoke fixture to Effect 4.1.2 and extended the instrumentation version matrix.
5. Included the pinned-version picker and exact npm-version validation described in the changelog.
   Its change missed the published 0.1.9 build; 0.1.10 is the first release that ships it.
6. Set plugin version 0.1.10, the stable compile and GA verifier pins, and Gradle 9.8.2.
7. Refreshed reference provenance, canary notes, smoke guidance, and this report. The parity matrix
   remains unchanged pending user-run WebStorm smoke.
8. Added `Use tsrs (TypeScript 7.1) for WebStorm TypeScript` (settings button and `Tools` action):
   it locates tsrs in the workspace `@tsrs/<platform>` or `@effect/tsgo-<platform>` package or the
   managed cache, generates the `typescript` 7+ package shape WebStorm's TypeScript 7 detection
   requires under the IDE system directory, selects it, and restarts WebStorm's TypeScript service
   through its stable `TypeScript.Restart.Service` action id. A platform test runs WebStorm's own
   `getTypeScriptGoPackageInfo` against the generated layout.
9. Listed `Fix all '<fix>' problems in file` directly in the Alt+Enter popup beside every Effect
   codemod fix, keeping the submenu entry.
10. Rebuilt "Fix all" as a multi-pass engine (`EffectFixAllPasses.kt`): repeat to a fixed point (at
    most 64 passes), pull diagnostics after each pass, undo any pass that adds an error-severity
    diagnostic (code + message multiset), retry rejected batches one fix at a time, and skip single
    fixes that still add errors. Driven by a per-rule inventory of all 147 rules: 527 upstream v4
    fixtures through the published 0.67.4 binary, 2,974 Effect diagnostics across 140 rules, every
    rule surfaced, all 71 codemod rules eligible. A JavaScript port of the engine over the same
    437 rule/document/title combinations ends with zero new errors in 437 (single pass: 419); the 15
    combinations with more remaining sites are exactly those whose single-pass result had introduced
    errors. Maximum 20 passes (`yieldInCallArgument_shapes.ts`).
11. Replaced the scheduled-for-removal `SimpleListCellRenderer.create(Customizer)` call with
    `textListCellRenderer`.

## Verification evidence

### Published native binary and instrumentation

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary <extracted @effect/tsgo-linux-x64@0.67.4>/lib/tsc
node scripts/verify-instrumentation.mjs
```

Recorded results: all five LSP lanes (`healthy`, `failing`, `new-diagnostics`,
`diagnostic-directives`, `fix-all`) pass against each of these packaged executables with workspace
`typescript@7.0.2` and `effect@4.1.2`:

- `lib/tsc` (7.0.2).
- `artifacts/typescript/7.1.0-beta/tsc`.
- `artifacts/tsrs/0.22.0-ts7.1.0-beta/tsrs`.

The new cases apply `Replace with Effect.as` and `Replace with Effect.flatten`. Initialize
advertises inlay hints, semantic tokens, and pull diagnostics, with no execute-command provider;
Layer Mermaid stays on hover transport.

The packaged tsrs is 43,123,960 bytes, mode `0755`, SHA-256
`b88418fe9c58ae802fefa9d4f2beb29a79bf38a6e764e58517c45afc8b7350b5`. Its version output is
`Version 7.1.0-beta+effect-tsgo.0.67.4 (tsrs 0.22.0-ts7.1.0-beta, microsoft/TypeScript@3e9236361fa9)`.
These protocol results do not qualify managed Rust selection or interactive IDE performance.

The instrumentation verifier passes on Effect `4.0.0-rc.112`, `4.0.0-rc.115`, `4.0.1`, `4.0.5`,
and `4.1.2`, covering span stacks, source/defect locations, interruption, and legacy/cache controls.
The focused 4.1.2 runtime probe also confirms completion-observer visibility before cache cleanup and
ignored late span mutations. Native and Node probes do not establish manual IDE parity.

### Release command

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

`BUILD SUCCESSFUL in 5m 55s` on the final tree: 157 tests, 0 failures, 0 errors;
`buildSearchableOptions` completed; Qodana (`qodana.recommended`, Qodana for JVM 2026.1.4) reported
0 problems.

| Target | Verdict |
| --- | --- |
| `WS-262.11879.105` (compile target) | Compatible. 51 usages of deprecated API |
| `WS-263.7307.202` (WebStorm 2026.3 GA) | Compatible. 53 usages of deprecated API |
| `IU-263.7307.200` (IntelliJ IDEA Ultimate 2026.3 GA) | Compatible. 53 usages of deprecated API |

The deprecated usages are the `LspServer*` family (deferred LspClient migration, issue #26) plus
`FileEditorManager.openFile`; the four added since 0.1.9 are `LspServer` references in the fix-all
session. No experimental or scheduled-for-removal API is used: `TypeScriptServiceRestarter` is
experimental, so the restart goes through the action id instead.

### Distribution

`build/distributions/effect-jetbrains-plugin-0.1.10.zip`, 5,351,359 bytes, SHA-256
`c7df43610d415f192b3c7e8b17cb878e15bd1403351da68f4025646b40c76641`; `unzip -t` reports no errors.

### Bounded sandbox boots

`runIdeVerifierWebStorm` booted WebStorm `263.7307.202` for a bounded 240 s run and logged
`Loaded custom plugins: Effect TSGO (dev.effect.jetbrains, 0.1.10)`. Its two SEVERE records
(`SlowOperations`, `TransactionGuardImpl` write-unsafe context) are both raised from the platform's
`DynamicPluginsFrameStateListener` with no plugin frames. The run ended at the timeout, and the
sandbox `.lock` / `.port` files were removed afterwards.

### Local installation

`scripts/install-local-webstorm-plugin.sh` declined because WebStorm 2026.3 was running. The
`WebStorm2026.3/effect-jetbrains-plugin` directory already held `effect-jetbrains-plugin-0.1.10.jar`,
byte-identical to this build. It was installed at 08:49:38, just before that WebStorm session started,
and not by this run's installer. The IDE was not stopped.

Manual editor, DevTools, and paused-debugger smoke per `SMOKE_CHECKLIST.md` remains owed and is
distinct from automated proof.

## Deferred scope

- `LspServer*` → `LspClient*` API migration (issue #26), deprecated since 2026.1.4 and still
  functional on 263.
- Managed tsrs (Rust) engine selection, pending interactive qualification; `MANUAL` trials remain
  available.
- Caching parsed diagnostic directives per document modification stamp.
- Non-modal bounded fix-all with one cancellation/timeout budget.
- Explicit `intellij.platform.debugger` module dependency check for 263 development builds.
- Oxlint execution stays with the official Oxc plugin, whose current inspected build declares
  `253`–`262`, excluding 263; qualify a compatible build before promising coexistence on 263.
- `docs/parity-matrix.md` remains untouched pending user WebStorm smoke.
- The `_effectGetLayerMermaid` execute-command bridge remains deferred; hover Mermaid is supported.
