# Getting Started

## What You Need

- A supported JetBrains IDE:
  - WebStorm `2026.2` (stable) or `2026.3` EAP
  - IntelliJ IDEA Ultimate `2026.2` or `2026.3` EAP
- A Java 25 toolchain for local builds against the `262.*` platform line (the compatibility range
  spans `262`–`263.*`).
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
    the packaged TypeScript builds (`lib/tsc` for the `latest` build and
    `artifacts/typescript/<version>/tsc` for the others since `@effect/tsgo` 0.32.0,
    `lib/tsc`/`lib/tsc-next` before that)

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
| `PINNED` | You need a stable version across projects or teammates | An exact `@effect/tsgo` version (for example `0.54.1`), network access to npm, and a supported workspace native TypeScript package whose `gitHead` exactly matches packaged binary metadata |

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
extraction. Current packages (0.52.0+) ship the `latest` TypeScript build as `lib/tsc` and the
other builds under `artifacts/typescript/<version>/`, described by a `lib/upstream.json` component
manifest (0.32.0–0.51.x shipped every build under `artifacts/` with `lib/tsc` as a compatibility
copy; earlier packages carried `lib/tsc`/`lib/tsc-next`); the plugin matches that metadata to the
native TypeScript package installed in the workspace.

### Pinned version picker

In `PINNED` mode the `Pinned version` field lists the exact `@effect/tsgo` versions the workspace
already depends on: `node_modules/@effect/tsgo`, the `@effect/tsgo-<platform>` packages in any
ancestor `node_modules` (nearest first), and an exact `@effect/tsgo` specifier in the workspace
`package.json`. Selecting `PINNED` with an empty field fills in the nearest detected version, so the
IDE runs the same release as the project's CLI. The `Select…` entry opens a file chooser; pick an
`@effect/tsgo` package directory (or its `package.json`) to pin that package's version. You can
still type any exact npm version. The field accepts only exact versions such as `0.54.1`: a path,
range, or URL is rejected before anything is downloaded, because `PINNED` always fetches from npm.
To run a binary that is already on disk, use `MANUAL` and point it at the executable (for example
`node_modules/@effect/tsgo-linux-x64/lib/tsc`).

### WebStorm TypeScript settings

The plugin launches the Effect-patched native compiler itself as a complete TypeScript language
server. It never reads `Languages & Frameworks | TypeScript`, and that page's `TypeScript:` field
expects a TypeScript package directory (`node_modules/typescript` or the bundled native build), not
the tsgo executable. Upstream recommends running `effect-tsgo` instead of the stock service, not
alongside it: leave `TypeScript:` on the workspace package and uncheck `TypeScript language service`
on that page so the two servers do not report duplicate diagnostics. If you keep the built-in
service on, expect each Effect-aware finding to appear once from the plugin and the plain TypeScript
findings to appear twice.

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
