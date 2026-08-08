# Getting Started

## What You Need

- A supported JetBrains IDE:
  - WebStorm `2026.2` EAP
  - IntelliJ IDEA Ultimate `2026.2` EAP
- A Java 25 toolchain for local builds against the `262.*` EAP platform line.
- A project with supported TypeScript or JavaScript files:
  - `.ts`
  - `.tsx`
  - `.cts`
  - `.mts`
  - `.js`
  - `.jsx`
  - `.cjs`
  - `.mjs`
- One of the following binary strategies:
  - A `MANUAL` path to an executable Effect-patched native `tsc` (or legacy `tsgo`) binary; this
    mode does not require a native TypeScript package in the workspace
  - Plugin-managed `LATEST` or `PINNED` after you explicitly choose managed npm downloads; these
    modes require `typescript >= 7`, `@typescript/native`, an npm alias, or the legacy
    `@typescript/native-preview` package whose workspace `gitHead` metadata exactly matches one of
    the packaged TypeScript builds (`artifacts/typescript/<version>/tsc` since `@effect/tsgo`
    0.32.0, `lib/tsc`/`lib/tsc-next` before that)

Community Edition and Android Studio are out of scope for this plugin.

## Install The Plugin

The repository currently documents local installation from source:

```bash
./gradlew build
```

Install the generated ZIP from `build/distributions/` using your IDE's `Install Plugin from Disk`
action.

Restart the IDE after install or update. The plugin declares restart-required metadata so its LSP
support, status widget, settings page, and `Effect Dev Tools` tool window are registered during IDE
startup.

## Configure Effect Settings

Open `Settings | Tools | Effect`.

The plugin manages or launches `@effect/tsgo` directly. It does not patch JetBrains-managed or
project-managed TypeScript binaries.

### Binary modes

| Mode | When to use it | Requirements |
| --- | --- | --- |
| `MANUAL` | You already manage the binary yourself, or want no plugin-managed download | An executable Effect-patched native `tsc` or legacy `tsgo` path |
| `LATEST` | You want the newest published `@effect/tsgo` for your platform | Network access to npm and a supported workspace native TypeScript package whose `gitHead` exactly matches packaged binary metadata |
| `PINNED` | You need a stable version across projects or teammates | A specific `@effect/tsgo` version, network access to npm, and a supported workspace native TypeScript package whose `gitHead` exactly matches packaged binary metadata |

The plugin validates pinned versions, manual paths, JSON fields, the runtime server port, and the
metrics polling interval before applying settings.

`Initialization options JSON` and `Workspace configuration JSON` should be JSON objects. Arrays and
primitive values are rejected during validation.

### Manual binary example

If you already use npm to obtain `@effect/tsgo`, one way to discover a native executable path is:

```bash
npm exec --yes --package @effect/tsgo -- effect-tsgo get-exe-path
```

Use the resulting native executable path in `MANUAL` mode. Do not point manual mode at the
`effect-tsgo` package CLI wrapper; the plugin already supplies `--lsp --stdio` when it launches the
native server.

Managed modes download the complete npm platform package and validate npm tarball integrity before
extraction. Current packages (0.32.0+) ship one TypeScript build per version under
`artifacts/typescript/<version>/` plus a `lib/upstream.json` component manifest, keeping `lib/tsc`
as a compatibility copy of the `latest` build (earlier packages carried `lib/tsc`/`lib/tsc-next`);
the plugin matches that metadata to the native TypeScript package installed in the workspace.

## Start The Language Server

1. Save your settings.
2. Open a supported TypeScript or JavaScript file.
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
- A browser-backed `Tracer Web` tab appears when the IDE's JCEF runtime is available.
- The `Debug` tab can attach to a paused debug session, inject optional Node.js instrumentation for
  configured JetBrains Node run/debug profiles, and show best-effort interactive `Context`,
  `Span Stack`, `Fibers`, and `Breakpoints` snapshot trees when instrumentation is available.

Continue with the [usage guide](usage.md) once the plugin is installed and running.
