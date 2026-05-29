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
| `Binary` | Choose `LATEST`, `PINNED`, or `MANUAL`, set a pinned version, or provide a manual binary path |
| `Language Server` | Pass extra environment variables, initialization options JSON, and workspace configuration JSON |
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
| Local Mermaid graph action | Adapted | Editor/Tools action opens Mermaid source when the LSP advertises the Effect execute-command bridge |

The table above describes the shipped surface area. Recorded manual editor smoke and richer
real-binary semantic evidence are still explicit follow-up validation items in this repo.

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

The `Debug` tab is intentionally honest about current scope:

- It can attach to the active JetBrains debug session.
- It can optionally inject bundled Effect instrumentation into Node.js run/debug configurations.
- It can refresh best-effort live Effect `Context`, `Span Stack`, `Fibers`, and `Breakpoints`
  snapshots while the debug session is paused.
- It can toggle pause-on-defects and interrupt the current Effect fiber when instrumentation has
  observed one.

This is an adapted JetBrains surface, not full VS Code debug-sidebar parity.

## Deferred And Adapted Areas

| Area | Current state |
| --- | --- |
| Live debugger snapshots | Adapted; available while the attached session is paused and instrumentation is installed |
| Automatic Node.js run/debug instrumentation injection | Adapted |
| Optional advanced tracer / JCEF panel | Adapted |
| Local Mermaid graph action | Adapted; requires an Effect `tsgo` build with `_effectGetLayerMermaid` execute-command support |
| Literal VS Code layout parity | Not a goal; JetBrains-native adapted UX is the current direction |

Use [troubleshooting](troubleshooting.md) if startup or runtime behavior does not match the flow
above.
