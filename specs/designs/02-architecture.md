# Design: Architecture

## Plugin shape
- Single Gradle module based on the local IntelliJ Platform template
- Kotlin-only implementation
- Plugin dependencies locked to:
  - `com.intellij.modules.platform`
  - `com.intellij.modules.ultimate`
  - `com.intellij.modules.lsp`
- Root package: `dev.effect.intellij`

## Package layout

```text
src/main/kotlin/dev/effect/intellij/
  actions/
  binary/
  debug/
  devtools/
  lsp/
  settings/
  status/
  ui/
  webview/
src/main/resources/
  META-INF/plugin.xml
  icons/
  messages/
src/test/kotlin/dev/effect/intellij/
src/test/testData/
  fixtures/lsp/
  fixtures/devtools/
  fixtures/debug/
```

## Named subsystems

| Subsystem | Primary package | Lifetime | Responsibilities |
| --- | --- | --- | --- |
| Settings | `settings` | project + application | Persist user-facing config, merge defaults, validate edits |
| Binary management | `binary` | application | Resolve package version, install/download, cache, validate executable, expose cache metadata |
| LSP integration | `lsp` | project | Register support provider, launch descriptor, translate settings into process/env/init config |
| Status and diagnostics | `status` | project | Track server lifecycle, publish widget state, route user-facing errors and log links |
| Dev Tools runtime | `devtools` | project | Host the in-plugin runtime server, manage connected clients, metrics, tracer snapshots, and active client |
| Debug bridge | `debug` | project | Observe JetBrains debug sessions, expose Effect-specific snapshots, coordinate attach and instrumentation affordances |
| Tool window UI | `ui` | project | Build settings screens, tool window tabs, trees, tables, detail panes, and toolbar actions |
| Optional web tracer | `webview` | project | Create JCEF-backed tracer panel when supported, otherwise defer to the Swing tracer surface |

## Service layout

### Application services
- `EffectApplicationStateService`
  - machine-local state only
  - download/cache directory preference
  - remembered UI preference for advanced tracer mode
- `EffectBinaryService`
  - resolves and installs `@effect/tsgo`
  - owns cache metadata and platform package selection
- `EffectNotificationService`
  - central place for actionable notifications and log-routing helpers

### Project services
- `EffectProjectSettingsService`
  - owns all user-facing project configuration
  - validates JSON/env/path fields
  - produces `ResolvedEffectSettings`
- `EffectLspProjectService`
  - owns the current server descriptor/session for the project
  - responds to settings changes and restarts
- `EffectStatusService`
  - reduces LSP lifecycle + validation state into widget/output state
- `EffectDevToolsService`
  - owns runtime server state, connected clients, active client, metrics, and tracer snapshots
- `EffectDebugBridgeService`
  - tracks the active debug session
  - exposes context/span/fiber/breakpoint snapshots when instrumentation is available

## Public state contracts

### Project-scoped settings contract
`EffectProjectSettings` is the canonical user-facing settings shape.

- `binaryMode`: `LATEST`, `PINNED`, `MANUAL`
- `pinnedVersion`: nullable string
- `manualBinaryPath`: nullable path
- `extraEnv`: string map
- `initializationOptionsJson`: nullable JSON string
- `workspaceConfigurationJson`: nullable JSON string
- `devToolsPort`: integer, default `34437`
- `metricsPollIntervalMs`: integer, default `500`
- `spanStackIgnoreList`: string list
- `injectNodeOptions`: boolean, default `false`
- `injectDebugConfigurationTypes`: string list

### Application-scoped state contract
`EffectApplicationState` is machine-local and not intended to change project semantics.

- `binaryCacheDirOverride`: nullable path
- `preferredTracerMode`: `TREE_ONLY` or `AUTO_JCEF`
- `showAdvancedTracerWhenAvailable`: boolean

### Derived contracts
- `ResolvedEffectSettings`
  - validated, effective project configuration
  - includes env map, parsed JSON payloads, and feature flags
- `BinaryResolution`
  - selected mode, resolved version, executable path, source, and validation status
- `LspLaunchConfiguration`
  - command line, working directory, env, initialization options, workspace config
- `DevToolsRuntimeState`
  - server status, port, active client id, connected clients, last error
- `DebugBridgeState`
  - active session id, instrumentation availability, attach status, snapshot timestamps

## Core service contracts

### Settings
- `resolve(project): ResolvedEffectSettings`
- `validate(settings): List<SettingProblem>`

### Binary
- `resolve(project): BinaryResolution`
- `ensureAvailable(project): BinaryResolution`
- `invalidate(project)`

### LSP
- `createLaunchConfiguration(project, settings, resolution): LspLaunchConfiguration`
- `restart(project, reason)`

### Dev Tools
- `startServer(project)`
- `stopServer(project)`
- `selectActiveClient(project, clientId)`
- `resetMetrics(project)`
- `resetTracer(project)`

### Debug bridge
- `attachToSession(project, sessionId)`
- `detach(project)`
- `refreshSnapshots(project)`

## Data flow

### LSP lifecycle
1. A supported TypeScript file opens.
2. `EffectProjectSettingsService` resolves and validates project settings.
3. `EffectBinaryService` resolves or installs the correct `@effect/tsgo` binary.
4. `EffectLspProjectService` builds `LspLaunchConfiguration`.
5. The plugin launches `@effect/tsgo --lsp --stdio`.
6. `EffectStatusService` updates the widget and notifications.

### Runtime Dev Tools lifecycle
1. The user opens `Effect Dev Tools`.
2. `EffectDevToolsService` shows runtime-server state and the current active client.
3. Starting the runtime server opens the project-local transport endpoint.
4. Client, metrics, and tracer tabs render from the shared service state.

### Debug lifecycle
1. A JetBrains debug session starts or the user explicitly attaches it.
2. `EffectDebugBridgeService` determines whether instrumentation is present.
3. If present, the bridge produces context/span/fiber/breakpoint snapshots for the tool window.
4. If absent, the bridge exposes a structured empty state and no-op actions instead of faking parity.

## Explicit decisions
- All user-visible feature settings are project-scoped so repos can pin versions safely.
- Application state is limited to machine-local cache and presentation preferences.
- Language-server status belongs in the LSP widget; Dev Tools runtime state belongs in the tool window.
- Base Dev Tools surfaces must work without any debugger session.
- JCEF is optional and never the only tracer path.
- Local layer-mermaid preview stays deferred until a custom transport is designed on both client and server sides.
