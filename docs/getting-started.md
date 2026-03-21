# Getting Started

## What You Need

- A supported JetBrains IDE:
  - WebStorm `2025.3.x`
  - IntelliJ IDEA Ultimate `2025.3.x`
- A project with supported TypeScript files:
  - `.ts`
  - `.tsx`
  - `.cts`
  - `.mts`
- One of the following binary strategies:
  - Plugin-managed `LATEST`
  - Plugin-managed `PINNED`
  - A `MANUAL` path to an executable native `tsgo` binary

Community Edition and Android Studio are out of scope for this plugin.

## Install The Plugin

The repository currently documents local installation from source:

```bash
./gradlew build
```

Install the generated ZIP from `build/distributions/` using your IDE's `Install Plugin from Disk`
action.

## Configure Effect Settings

Open `Settings | Tools | Effect`.

The plugin manages or launches `@effect/tsgo` directly. It does not patch JetBrains-managed or
project-managed TypeScript binaries.

### Binary modes

| Mode | When to use it | Requirements |
| --- | --- | --- |
| `LATEST` | You want the newest published `@effect/tsgo` for your platform | Network access to npm during resolution |
| `PINNED` | You need a stable version across projects or teammates | A specific `@effect/tsgo` version string |
| `MANUAL` | You already manage the binary yourself | An executable native `tsgo` path |

The plugin validates pinned versions, manual paths, JSON fields, the runtime server port, and the
metrics polling interval before applying settings.

`Initialization options JSON` and `Workspace configuration JSON` should be JSON objects. Arrays and
primitive values are rejected during validation.

### Manual binary example

If you already use npm to obtain `@effect/tsgo`, one way to discover a native executable path is:

```bash
npm exec --yes --package @effect/tsgo -- effect-tsgo get-exe-path
```

Use the resulting executable path in `MANUAL` mode.

## Start The Language Server

1. Save your settings.
2. Open a supported TypeScript file.
3. Watch the Effect LSP widget move through startup states until it reaches `Running`.

The widget is the plugin's status-bar surface for:

- current LSP status
- restart
- opening settings
- opening the IDE log directory
- focusing `Effect Dev Tools`

## Start Runtime Dev Tools

The runtime server does not auto-start on first run.

To use runtime metrics and tracing:

1. Open the `Effect Dev Tools` tool window.
2. Start the runtime server from the toolbar.
3. Connect an Effect runtime client to the local server.
4. Select the active client to inspect metrics and tracer data.

The default runtime server port is `34437`.

## What To Expect On Day One

- LSP settings are project-scoped.
- Runtime metrics are polled from connected clients.
- Tracer data is streamed from runtime span events.
- The `Debug` tab is present, but it currently provides attach/setup guidance instead of live
  `Context`, `Span Stack`, `Fibers`, or `Breakpoints` snapshots.

Continue with the [usage guide](usage.md) once the plugin is installed and running.
