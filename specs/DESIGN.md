# DESIGN: Effect JetBrains Plugin

## Canonical decisions
- Build one Kotlin-based JetBrains plugin under the root package `dev.effect.intellij`.
- Target WebStorm and IntelliJ IDEA Ultimate on `2025.3.x`; treat `2025.2.2` only as the earliest acceptable bootstrap floor.
- Launch `@effect/tsgo` directly with `--lsp --stdio`.
- Support three binary modes: latest, pinned, and manual path.
- Keep all user-visible feature settings project-scoped.
- Use one `Effect Dev Tools` tool window for runtime and later debugger surfaces.
- Use the LSP widget as the only status-bar surface.
- Treat JCEF as optional and keep the Swing tracer as the guaranteed fallback.
- Defer local layer-mermaid preview parity until a custom client/server transport exists.

## Architecture

| Subsystem | Package | Lifetime | Responsibilities |
| --- | --- | --- | --- |
| Settings | `dev.effect.intellij.settings` | project + application | Persist settings, merge defaults, validate config |
| Binary management | `dev.effect.intellij.binary` | application | Resolve, install, cache, validate `@effect/tsgo` |
| LSP integration | `dev.effect.intellij.lsp` | project | Support provider, descriptor, launch config, restart flow |
| Status | `dev.effect.intellij.status` | project | LSP widget state, startup failures, log/output links |
| Dev Tools runtime | `dev.effect.intellij.devtools` | project | Runtime server, clients, active client, metrics, tracer state |
| Debug bridge | `dev.effect.intellij.debug` | project | Session attach, instrumentation checks, debug snapshots |
| UI surfaces | `dev.effect.intellij.ui` | project | Configurable, tool window, trees, tables, detail panes, actions |
| Optional web tracer | `dev.effect.intellij.webview` | project | JCEF tracer panel when supported |

### Service layout
Application services:
- `EffectApplicationStateService`
- `EffectBinaryService`
- `EffectNotificationService`

Project services:
- `EffectProjectSettingsService`
- `EffectLspProjectService`
- `EffectStatusService`
- `EffectDevToolsService`
- `EffectDebugBridgeService`

### Required plugin dependencies
- `com.intellij.modules.platform`
- `com.intellij.modules.ultimate`
- `com.intellij.modules.lsp`

## Public settings and contracts

### Project settings contract
`EffectProjectSettings`
- `binaryMode`: `LATEST | PINNED | MANUAL`
- `pinnedVersion`
- `manualBinaryPath`
- `extraEnv`
- `initializationOptionsJson`
- `workspaceConfigurationJson`
- `devToolsPort`
- `metricsPollIntervalMs`
- `spanStackIgnoreList`
- `injectNodeOptions`
- `injectDebugConfigurationTypes`

### Application state contract
`EffectApplicationState`
- `binaryCacheDirOverride`
- `preferredTracerMode`
- `showAdvancedTracerWhenAvailable`

### Derived contracts
- `ResolvedEffectSettings`
- `BinaryResolution`
- `LspLaunchConfiguration`
- `DevToolsRuntimeState`
- `DebugBridgeState`

### Core service APIs
Settings:
- `resolve(project): ResolvedEffectSettings`
- `validate(settings): List<SettingProblem>`

Binary:
- `resolve(project): BinaryResolution`
- `ensureAvailable(project): BinaryResolution`
- `invalidate(project)`

LSP:
- `createLaunchConfiguration(project, settings, resolution): LspLaunchConfiguration`
- `restart(project, reason)`

Dev Tools:
- `startServer(project)`
- `stopServer(project)`
- `selectActiveClient(project, clientId)`
- `resetMetrics(project)`
- `resetTracer(project)`

Debug:
- `attachToSession(project, sessionId)`
- `detach(project)`
- `refreshSnapshots(project)`

## UI surfaces

### Settings page
Create one project-level configurable at `Settings | Tools | Effect` with sections for:
- `Binary`
- `Language Server`
- `Dev Tools`
- `Debugger`

Validation is explicit: required pinned version, required executable path for manual mode, JSON parsing, valid port and poll ranges.

### Status widget
Use the LSP widget created from the server descriptor. It exposes:
- `Not Configured`
- `Resolving Binary`
- `Starting`
- `Running`
- `Restart Required`
- `Error`

Actions:
- open settings
- restart server
- open logs/output
- focus `Effect Dev Tools`

The widget tracks only language-server state.

### Tool window
Create one tool window named `Effect Dev Tools`.

Shared toolbar actions:
- start/stop or restart runtime server
- open settings
- select active client
- reset metrics
- reset tracer
- attach active debug session later

Guaranteed tabs:
- `Clients`
- `Metrics`
- `Tracer`

Later debugger tab group:
- `Debug`
  - `Context`
  - `Span Stack`
  - `Fibers`
  - `Breakpoints`

The runtime tabs must work without any debugger integration.

## Milestone boundaries

### Milestone 1: foundation
- rename template
- upgrade baseline and plugin dependencies
- establish packages, services, and fixtures

### Milestone 2: core LSP parity
- project settings
- binary resolution and cache
- LSP launch/restart flow
- diagnostics, actions, completion, hover, inlay hints, symbols
- hover-based layer graph links
- LSP widget and startup diagnostics

This milestone is independently shippable.

### Milestone 3: runtime Dev Tools parity
- `Effect Dev Tools` tool window
- runtime server start/stop
- clients, metrics, tracer tree, and reset actions
- explicit empty states and runtime error handling

This milestone must not depend on debugger APIs or JCEF.

### Milestone 4: debugger and advanced parity
- debug bridge and session-aware snapshots
- attach/debug actions
- instrumentation affordances
- optional JCEF tracer panel
- any future custom transport for local Mermaid preview

## Fallback behavior for parity gaps

| Gap | Locked fallback |
| --- | --- |
| JCEF unsupported | Stay on the Swing `Tracer` tab with tree + detail panes |
| Advanced tracer delayed | Ship only the Swing `Tracer` tab |
| Debug bridge delayed | Ship only runtime tabs; do not add a fake debug surface |
| Instrumentation missing in a live debug session | Show a setup/attach empty state in `Debug` |
| VS Code local Mermaid preview command | Keep hover links only; no button until transport exists |
| VS Code debug-sidebar layout | Use tool-window focus/actions instead of cloning VS Code panes |

## Non-negotiable constraints
- No IDE binary patching
- No project `node_modules` coupling for core binary management
- No Community Edition or Android Studio support
- No assumption that JCEF is always available
