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
| Inlay hints | Implemented | Available on the locked `2026.2` EAP baseline |
| Document and workspace symbols | Implemented | Expected on the `2026.2` EAP baseline |
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

The current real-binary smoke target is `@effect/tsgo@0.14.3`. That version includes the
`catchToOrElseSucceed`, `redundantOrDie`, and `schemaNumber` diagnostics added after the previous
`0.11.4` reference import. Effect v4 samples must install `effect@beta` or an explicit
`effect@4.0.0-beta.78` version because the npm `effect` `latest` tag is still v3.

For common language-service options, prefer the typed settings controls. They emit `effect.*`
workspace configuration only when explicitly set, and they override duplicate raw JSON keys. Keep
`Workspace configuration JSON` for advanced options that do not yet have a dedicated control.

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
      "redundantOrDie": "warning",
      "schemaNumber": "warning"
    }
  }
}
```

### Recorded Real-Binary Smoke

On June 8, 2026, the real-binary verifier was run against the native Linux x64 npm binary for
`@effect/tsgo@0.14.1` with fixture workspaces installing `effect@beta` (`4.0.0-beta.78`).

Command:

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary /home/elpresidank/.npm/_npx/87bbd351d137307a/node_modules/@effect/tsgo-linux-x64/lib/tsgo
```

Observed through LSP:

- Healthy fixture: Layer hover includes Mermaid links, completion returned `44` items, document symbols
  included `Database`, `Cache`, and `appLayer`, workspace symbol search found `Database`, and inlay hints
  completed with no current hints.
- Failing fixture: `missingStarInYieldEffectGen` diagnostic `377008` appeared with quick fixes including
  `Replace yield with yield*`.
- New diagnostics fixture: `catchToOrElseSucceed`, `redundantOrDie`, and `schemaNumber` diagnostics
  appeared. Code actions included `Replace with Effect.orElseSucceed`, `Replace with Schema.Finite`,
  and `Replace with Schema.FiniteFromString`.
- The published `0.14.1` server did not advertise `executeCommandProvider.commands`, so the local
  Mermaid graph action remains experimental; hover Mermaid links are the supported path.

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
