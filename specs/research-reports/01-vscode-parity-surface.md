# Research Report: VS Code Parity Surface

## Summary
`.repos/vscode-extension` is an Effect Dev Tools extension, not a direct `@effect/tsgo` editor integration. It is still the richest local reference for runtime clients, metrics, tracing, debugger-bound inspection, instrumentation injection, and the extra local layer-mermaid preview command.

## Main findings

### Product shape
- Display name: `Effect Dev Tools`
- Activation: `onDebug`
- Main surfaces:
  - Activity bar container `effect`
  - Views: `Clients`, `Tracer`, `Metrics`
  - Debug views: `Effect Context`, `Effect Span Stack`, `Effect Fibers`, `Effect Breakpoints`
  - Panel webview: `Effect Tracer`

### Settings
The extension contributes six settings:

| Setting | Default | Purpose |
| --- | --- | --- |
| `effect.devServer.port` | `34437` | WebSocket server port |
| `effect.metrics.pollInterval` | `500` | Metrics refresh |
| `effect.tracer.pollInterval` | `250` | Tracer refresh for debug transport |
| `effect.spanStack.ignoreList` | `[]` | Filters spans in debug stack view |
| `effect.instrumentation.injectNodeOptions` | `false` | Auto-inject instrumentation into debug configs |
| `effect.instrumentation.injectDebugConfigurations` | `["node","node-terminal","pwa-node"]` | Debug config types eligible for injection |

### Commands
User-facing commands include:

- `effect.startServer`
- `effect.stopServer`
- `effect.attachDebugSessionClient`
- `effect.resetMetrics`
- `effect.resetTracer`
- `effect.copyInfoValue`
- `effect.revealSpanLocation`
- `effect.revealFiberCurrentSpan`
- `effect.interruptDebugFiber`
- `effect.resetTracerExtended`
- `effect.enableSpanStackIgnoreList`
- `effect.disableSpanStackIgnoreList`
- `effect.showLayerMermaid`
- `effect.togglePauseOnDefects`

There is also an internal selection command: `effect.selectClient`.

## Parity inventory

| Surface | What VS Code does | JetBrains mapping | Target parity | Risk | Primary local source |
| --- | --- | --- | --- | --- | --- |
| Clients server and active client | Runs an in-IDE WebSocket server, tracks state/errors/port, lists clients, and lets the user select the active client | `Effect Dev Tools` tool window tab | Adapted | Low | `package.json`, `src/Clients.ts`, `src/ClientsProvider.ts` |
| Metrics view | Polls the active client, normalizes metrics by name, renders counters/gauges/histograms/summaries/frequencies | Tool window tree/tab | Adapted | Low | `src/MetricsProvider.ts` |
| Tracer tree | Streams spans and span events, builds a tree, supports reset | Tool window tree/tab | Adapted | Low | `src/SpanProvider.ts`, `src/TreeCommands.ts` |
| Tracer webview | Hosts a retained React/Vite webview, streams spans to it, supports reset and go-to-location | JCEF panel when available, fallback otherwise | Adapted | Medium | `src/TracerProvider.ts`, `tracer/` |
| Attach debug session as client | Creates a Dev Tools client backed by the active debug session and polls it through instrumentation | Debug-session action plus bridge service | Adapted | High | `src/Clients.ts`, `src/DebugEnv.ts`, `src/DebugChannel.ts` |
| Debug context | Reads fiber context from the active debug session and exposes it as a tree | Session-aware tool window tree | Adapted | High | `src/ContextProvider.ts`, `src/DebugEnv.ts` |
| Debug span stack | Reads current span stack, supports ignore-list filtering, reveal-to-source, and attribute expansion | Session-aware tool window tree | Adapted | High | `src/DebugSpanStackProvider.ts`, `src/DebugEnv.ts` |
| Debug fibers | Lists fibers, stack/location metadata, children, attributes, and interrupt action | Session-aware tool window tree plus actions | Adapted | High | `src/DebugFibersProvider.ts`, `src/DebugEnv.ts` |
| Debug breakpoints and pause-on-defects | Tracks pause-on-defects state per thread and exposes reveal values captured on debug pause | Session-aware tool window plus debugger action | Adapted | High | `src/DebugBreakpointsProvider.ts`, `src/DebugEnv.ts` |
| Instrumentation injection | Injects `NODE_OPTIONS=--require .../instrumentation.global.js` into selected Node debug configurations and can also inject by evaluation at runtime | Dedicated run/debug settings or later parity item | Adapted | Medium | `src/InjectNodeOptionsInstrumentationProvider.ts`, `src/DebugEnv.ts` |
| Local layer-mermaid preview command | Calls VS Code's TypeScript extension with `typescript.tsserverRequest` and `_effectGetLayerMermaid`, then opens a local preview | Custom LSP request/notification or deferred parity | Blocked for exact parity | High | `src/LayerHoverProvider.ts` |

## Debugger dependency chain

- `DebugEnv` tracks the active debug session and listens to debug-adapter `stopped` / `continued` events.
- `DebugChannel` talks to the debugger through DAP requests such as `threads`, `stackTrace`, `evaluate`, and `variables`.
- The runtime inspection views are not passive trees; they depend on injected Effect instrumentation plus expression evaluation against the paused process.

This means JetBrains parity is not just a UI-porting exercise. It needs a real debug bridge and a session-aware state model.

## Recommendation
Treat this repository as the reference for a separate `Dev Tools parity` workstream. Stage it after core `@effect/tsgo` parity, and stage the debugger-bound pieces after the basic tool-window surfaces.

## Source checkpoints
- `.repos/vscode-extension/package.json`
- `.repos/vscode-extension/src/extension.ts`
- `.repos/vscode-extension/src/Clients.ts`
- `.repos/vscode-extension/src/ClientsProvider.ts`
- `.repos/vscode-extension/src/MetricsProvider.ts`
- `.repos/vscode-extension/src/SpanProvider.ts`
- `.repos/vscode-extension/src/TracerProvider.ts`
- `.repos/vscode-extension/src/ContextProvider.ts`
- `.repos/vscode-extension/src/DebugSpanStackProvider.ts`
- `.repos/vscode-extension/src/DebugFibersProvider.ts`
- `.repos/vscode-extension/src/DebugBreakpointsProvider.ts`
- `.repos/vscode-extension/src/DebugEnv.ts`
- `.repos/vscode-extension/src/DebugChannel.ts`
- `.repos/vscode-extension/src/InjectNodeOptionsInstrumentationProvider.ts`
- `.repos/vscode-extension/src/LayerHoverProvider.ts`
