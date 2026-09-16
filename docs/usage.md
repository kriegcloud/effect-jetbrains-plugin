# Usage Guide

## Plugin Surfaces

The current plugin experience is centered on three JetBrains-native surfaces:

- `Settings | Tools | Effect`
- the Effect LSP widget
- the `Effect Dev Tools` tool window

## Settings Page

The project-level Effect settings page is split into these sections:

| Section | Current purpose |
| --- | --- |
| `Binary` | Configure the default `MANUAL` path, or explicitly opt into managed `LATEST` / `PINNED` npm downloads |
| `Language Server` | Pass extra environment variables, initialization options JSON, common typed `@effect/tsgo` options, and advanced workspace configuration JSON |
| `Dev Tools` | Configure the runtime server port and metrics polling interval |
| `Debugger` | Configure instrumentation injection and show current debugger bridge status |

All user-visible Effect settings are project-scoped.

## LSP Features

The plugin is intended to expose the standard `@effect/tsgo` editor surface in supported
TypeScript and JavaScript files.

| Feature | Status | Notes |
| --- | --- | --- |
| Diagnostics | Implemented | Delivered through JetBrains LSP support |
| Code actions | Implemented | Uses standard LSP code-action flows |
| Completion | Implemented | Uses standard LSP completion |
| Hover | Implemented | Uses standard LSP hover / quick documentation |
| Inlay hints | Implemented | Available on the `262.*`/`263.*` platform baseline |
| Document and workspace symbols | Implemented | Expected on the `262.*`/`263.*` platform baseline |
| Hover-based layer graph links | Implemented | This is the supported layer-graph path today |
| Local Mermaid graph action | Experimental | Editor/Tools action opens Mermaid source when the LSP advertises the Effect execute-command bridge |

The table above describes the shipped surface area. Recorded real-binary LSP smoke exists for the
fixtures below; full manual IDE/editor smoke and broader semantic coverage remain follow-up validation
items in this repo.

Effect diagnostic directive comments such as `// @effect-diagnostics-next-line strictEffectProvide:off`
and `/** @effect-diagnostics floatingEffect:skip-file */` are honored before LSP diagnostics become
JetBrains editor annotations. This keeps manual server configurations from showing stale red squiggles
for diagnostics that the source has explicitly disabled.

### Current TSGO Smoke Targets

The current recorded real-binary smoke target is `@effect/tsgo@0.45.0` with
`typescript@7.0.2` (`gitHead` `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`) and
`effect@4.0.0-rc.115`. In addition to the existing
`catchToOrElseSucceed`, `redundantOrDie`, `schemaNumber`, `newSchemaClass`, `catchToIgnore`
(published as of 0.16.0), fixable `flatMapToMap` (as of 0.19.0), `missingPipeableSignature`
(0.21.0, off by default), `schemaOpaqueInstanceMember` (0.22.0, **error by default**, Effect v4
only), fixable `syncToSucceed` (0.23.0), and fixable `preferSchemaTypeProperty` (0.24.0, off by
default) coverage, the 0.25–0.27 line adds seven diagnostics: `schemaLiteralNonFinite` and
`floatingEffectInVitest` (both 0.25.0, **error by default**), and `abortControllerInEffect`,
`catchTagToCatchReason` (Effect v4 only), `catchChainToFirstSuccessOf`, the fixable
`preferUnsafeConstructor`, and `promiseInEffectSuccess` (warning by default) from 0.26.0.
`newSchemaClass` remains off by default. The 0.28–0.33 line adds one more: the fixable
`preferTypedSchemaDecoder` (0.33.0, suggestion by default, Effect v4 only), which offers a
`Replace with decodeSync`-style typed decoder rewrite; 0.31.0 also added an `Add yield* statement`
quick fix for `floatingEffect` findings inside yieldable `Effect.gen` contexts.

The 0.45.0 refresh adds published-binary coverage for `obsoleteSchemaImport` (Effect v4 warning,
suppression actions only), `preferSucceedSomeOrNone` and `allOfMapToForEach` (suggestions enabled at
warning in the fixture, with applied quick-fix edits), and `schemaSync` (off by default, enabled for
one line by a directive). The four rules deferred in the previous refresh—`allOfMapToForEach`,
`mapSomeToAsSome`, `catchDieToOrDie`, and `catchConditionalRefailToCatchIf`—are now published and
included in directive completion. Completion covers all 113 published rules; the real-binary
fixtures exercise a representative subset.

Three rules at the recorded source pin are newer than the 0.45.0 tag:
`catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and `catchRefailToTapError`. They remain
excluded from completion and published-binary claims. Effect v4 samples must install the exact
`effect@4.0.0-rc.115` smoke target because the `rc` dist-tag is a moving convenience alias and npm
`effect` `latest` remains v3.

Upstream rule metadata now marks `genericEffectServices` as v3-only and `schemaSyncInEffect` as
v3 + v4. `unsafeEffectTypeAssertion` moves from `effectNative` to `correctness`, including its
Oxlint preset placement. `schemaSync` defaults off in the language service, while the upstream
effect-native preset enables it at warning. These are upstream rule and preset choices; the plugin
consumes diagnostics and code actions through LSP. Oxlint and tsgolint remain upstream-managed
package contents, with no IDE-owned configuration or process lifecycle.

The upstream CLI also adds `diagnostics --list-files`, which reports each file's `file`,
`detectedEffect`, and `supportedEffect` values (and optional JSON `files`). The plugin's direct
`--lsp --stdio` launch is unchanged.

For common language-service options, prefer the typed settings controls. They emit `effect.*`
workspace configuration only when explicitly set, and they override duplicate raw JSON keys. Keep
`Workspace configuration JSON` for advanced options that do not yet have a dedicated control.

> **Where Effect options are read.** Current `@effect/tsgo` builds parse Effect language-service
> options (`inlays`, `mermaidProvider`, `noExternal`, `layerGraphFollowDepth`, `diagnosticSeverity`,
> …) from `tsconfig.json` &rarr; `compilerOptions.plugins` (the `@effect/language-service` entry) via
> `program.Options().Effect`. They are **not** read from LSP `initializationOptions` or
> `workspace/configuration`. The plugin still sends the typed values over LSP for forward
> compatibility, but to change server behavior today, set them in the workspace `tsconfig.json`.

Equivalent raw JSON example:

```json
{
  "effect": {
    "inlays": true,
    "mermaidProvider": "mermaid.live",
    "noExternal": false,
    "layerGraphFollowDepth": 1,
    "diagnosticSeverity": {
      "catchToOrElseSucceed": "warning",
      "catchToIgnore": "warning",
      "flatMapToMap": "warning",
      "redundantOrDie": "warning",
      "schemaNumber": "warning",
      "newSchemaClass": "warning",
      "syncToSucceed": "warning",
      "schemaOpaqueInstanceMember": "error",
      "missingPipeableSignature": "off",
      "preferSchemaTypeProperty": "off",
      "preferUnsafeConstructor": "suggestion",
      "promiseInEffectSuccess": "warning",
      "floatingEffectInVitest": "error",
      "schemaLiteralNonFinite": "error"
    }
  }
}
```

### Recorded Real-Binary Smoke

On September 16, 2026, the real-binary verifier was run against the matching native Linux x64 npm binary
for `@effect/tsgo@0.45.0`; fixture workspaces install the validated `typescript@7.0.2` package
(`gitHead` `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`, confirmed identical to the tarball's
`lib/upstream.json` `typescript.latest` component) and the explicit `effect@4.0.0-rc.115` release.
All four lanes passed against that fixed pair.

Command (since 0.32.0 the packaged executables live under `artifacts/typescript/<version>/`; the
`lib/tsc` compatibility copy of the `latest` build also works):

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/@effect/tsgo-linux-x64/artifacts/typescript/7.0.2/tsc
```

Observed through LSP:

- Healthy fixture: Layer hover includes Mermaid links, completion returned `44` items, document symbols
  included `Database`, `Cache`, and `appLayer`, workspace symbol search found `Database`, and inlay hints
  completed with no current hints.
- Failing fixture: `missingStarInYieldEffectGen` diagnostic appeared with quick fixes including
  `Replace yield with yield*`.
- New diagnostics fixture: `catchToOrElseSucceed`, `flatMapToMap`, `redundantOrDie`, `schemaNumber`,
  explicitly enabled `newSchemaClass`, `syncToSucceed`, `schemaOpaqueInstanceMember`,
  `preferUnsafeConstructor`, `promiseInEffectSuccess`, explicitly enabled `preferTypedSchemaDecoder`
  (0.33.0), and `floatingEffect` (inside a yieldable `Effect.gen` context) diagnostics appeared.
  Code actions included `Replace with Effect.orElseSucceed`, `Replace with Effect.map`,
  `Replace with Schema.Finite`, `Replace with Schema.FiniteFromString`,
  `Replace with Effect.succeed`, `Replace with Scope.makeUnsafe`, `Replace with decodeSync`
  (0.33.0), and `Add yield* statement` (the 0.31.0 floating-Effect quick fix).
- New published cases: `obsoleteSchemaImport` appeared once at warning severity with only the two
  suppression actions. `preferSucceedSomeOrNone` and `allOfMapToForEach` fixes produced
  `Effect.succeedSome(42)`, `Effect.succeedNone`, and `Effect.forEach`; applying the edits cleared
  those findings while preserving the fixture's one baseline error.
- Diagnostic-directive fixture: next-line and section directives suppressed the intended
  `strictEffectProvide` and `floatingEffect` findings, then surfaced them again when re-enabled.
  The next-line `schemaSync:warning` directive enabled exactly one warning and did not affect the
  default-off examples before or after it.
- The published `0.45.0` server still did not advertise `executeCommandProvider.commands`, so the local
  Mermaid graph action remains experimental; hover Mermaid links are the supported path.

### Runtime Instrumentation Smoke

The companion `scripts/verify-instrumentation.mjs` probe copies the repository's
[`smoke-app`](../src/test/testData/fixtures/runtime/smoke-app/) fixture into temporary workspaces,
installs exact Effect v4 release candidates 112 and 115, and injects the same instrumentation used
by the IDE:

```bash
node scripts/verify-instrumentation.mjs
```

Both versions passed span-stack, source/defect-location, and fiber-interruption assertions. Cache
and legacy-field controls exercise the rc.113+ transition and older runtime fallback. The fixture
also supplies a DevTools client, metrics, nested spans, a defect loop, an `Effect.never` long-runner,
and `example.ts` for the human editor pass. Follow its
[`SMOKE_CHECKLIST.md`](../src/test/testData/fixtures/runtime/smoke-app/SMOKE_CHECKLIST.md) for setup
and paused-session checks; automated Node results do not establish manual IDE parity.

## LSP Widget

The Effect LSP widget reports only language-server state.

### States

- `Not Configured`
- `Resolving Binary`
- `Checking For Update`
- `Downloading Binary`
- `Starting`
- `Running`
- `Restart Required`
- `Error`

### Actions

- `Restart`
- `Settings`
- `Logs`
- `Dev Tools`

Use the widget when you want to confirm startup state or trigger an LSP restart without reopening
settings.

## Effect Dev Tools

`Effect Dev Tools` is the home for runtime state and the current debugger guidance surface.

### Toolbar actions

- Start runtime server
- Stop runtime server
- Restart runtime server
- Select active client
- Open settings
- Reset metrics
- Reset tracer
- Attach the current debug session
- Refresh debug snapshots
- Toggle pause on defects
- Interrupt the current Effect fiber

### Tabs

| Tab | Status | What it shows today |
| --- | --- | --- |
| `Clients` | Implemented | Runtime server state, connected clients, selected-client details |
| `Metrics` | Implemented | Metrics for the active client, including metric details and tags |
| `Tracer` | Implemented | Span tree and span-event details for the active client |
| `Tracer Web` | Adapted | Browser-backed tracer view when JCEF is available |
| `Debug` | Adapted | Best-effort Context, Span Stack, Fibers, and Breakpoints snapshots from the active JetBrains debug session |

### Runtime model

- The runtime server listens locally inside the IDE.
- Metrics are requested from the selected client at the configured polling interval.
- Tracer updates come from streamed `Span` and `SpanEvent` protocol messages.
- Zero-client, empty, and runtime-error states are shown explicitly rather than hidden.

## Debug Tab

The `Debug` tab is the JetBrains-native adapted surface for VS Code's debug views:

- It can attach to the active JetBrains debug session.
- It can optionally inject bundled Effect instrumentation into Node.js run/debug configurations.
- It shows interactive `Context`, `Span Stack`, `Fibers`, and `Breakpoints` trees while the debug
  session is paused.
- It can copy selected debug values, reveal source locations when snapshot metadata includes a file,
  toggle span-stack ignore filtering, toggle pause-on-defects, and interrupt an interruptible fiber.

Runtime clients and paused debug-session inspection intentionally remain separate JetBrains surfaces.

## Deferred And Adapted Areas

| Area | Current state |
| --- | --- |
| Live debugger snapshots | Adapted; interactive trees are available while the attached session is paused and instrumentation is installed |
| Automatic Node.js run/debug instrumentation injection | Adapted; opt-in with a JetBrains run/debug allowlist and wildcard support |
| Optional advanced tracer / JCEF panel | Adapted |
| Local Mermaid graph action | Experimental; requires an Effect `tsgo` build with `_effectGetLayerMermaid` execute-command support |
| Literal VS Code layout parity | Not a goal; JetBrains-native adapted UX is the current direction |

For the detailed parity inventory, see [VS Code and Zed parity matrix](parity-matrix.md).

Use [troubleshooting](troubleshooting.md) if startup or runtime behavior does not match the flow
above.
