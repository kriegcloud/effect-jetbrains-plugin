# Effect TSGO for JetBrains

<!-- Plugin description -->
JetBrains plugin for `@effect/tsgo` language-server support and Effect runtime Dev Tools.
It targets the `2025.3.x` platform line, launches `@effect/tsgo` directly with `--lsp --stdio`,
and ships core LSP integration plus runtime Dev Tools today. Debugger surfaces are currently
guidance-first; live Effect snapshots, optional advanced tracer/JCEF work, and local Mermaid
preview transport remain deferred.
<!-- Plugin description end -->

## Overview

Effect TSGO for JetBrains brings `@effect/tsgo` into JetBrains IDEs and adds a local
`Effect Dev Tools` tool window for runtime clients, metrics, and tracer data.

The current plugin baseline is:

- Project-scoped Effect settings at `Settings | Tools | Effect`
- Direct binary launch through `@effect/tsgo --lsp --stdio`
- Managed `LATEST`, managed `PINNED`, and `MANUAL` binary modes
- An LSP widget for status, restart, logs, settings, and tool-window focus
- Runtime `Effect Dev Tools` tabs for `Clients`, `Metrics`, `Tracer`, and a guidance-first `Debug`
  surface

## Current Status

| Area | Status | Notes |
| --- | --- | --- |
| Core LSP wiring | Implemented | Direct binary launch, project settings, workspace/config passthrough, and widget actions are in place. |
| Editor features | Implemented | Diagnostics, code actions, completion, hover, inlay hints, symbols, and hover-based layer graph links are the intended supported surface; fuller real-IDE smoke evidence remains follow-up work. |
| Runtime Dev Tools | Implemented | Runtime server, client selection, metrics polling, tracer streaming, reset flows, and empty/error states are present. |
| Debugger surfaces | Adapted | The `Debug` tab exists, but it currently provides attach/setup guidance rather than live Effect runtime snapshots. |
| Advanced tracer / JCEF | Deferred | The Swing tracer is the current guaranteed baseline. |
| Local Mermaid preview transport | Deferred | Hover links are the supported layer-graph path today. |
| Supported-IDE manual editor smoke | Pending evidence | WebStorm sandbox boot and Plugin Verifier coverage are in place; recorded manual editor smoke remains follow-up work. |

## Supported IDEs

| IDE | Status | Notes |
| --- | --- | --- |
| WebStorm `2025.3.x` | Primary target | `runIde` is currently exercised against WebStorm `2025.3.3`. |
| IntelliJ IDEA Ultimate `2025.3.x` | Primary target | Supported by design and verifier coverage, with manual editor smoke still pending. |
| Unified PyCharm `2025.1+` | Later target | Not a current compatibility promise. |
| IntelliJ IDEA Community Edition | Unsupported | JetBrains public LSP support is out of scope here. |
| Android Studio | Unsupported | Not a supported target for this plugin. |

## Quick Start

1. Build the plugin ZIP:

   ```bash
   ./gradlew build
   ```

2. Install the plugin from disk in a supported JetBrains IDE using the artifact in
   `build/distributions/`.
3. Open `Settings | Tools | Effect` and choose a binary mode:
   - `LATEST` to resolve the newest published `@effect/tsgo`
   - `PINNED` to stay on an exact version
   - `MANUAL` to point at an executable native `tsgo` binary
4. Open a supported TypeScript file: `.ts`, `.tsx`, `.cts`, or `.mts`.
5. Confirm the Effect LSP widget reaches `Running`, then open `Effect Dev Tools` if you want
   runtime metrics or tracer data.

The plugin manages or launches `@effect/tsgo` directly. It does not patch JetBrains-managed or
project-managed TypeScript binaries.

## Documentation

- [Documentation hub](docs/README.md)
- [Getting started](docs/getting-started.md)
- [Usage guide](docs/usage.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Development guide](docs/development.md)

## Verification

The repository currently uses these primary validation commands:

```bash
./gradlew build
./gradlew check
timeout 90s ./gradlew runIde
./gradlew verifyPlugin
```

The shipped artifact has been verifier-compatible on recommended WebStorm builds. Full recorded
manual editor smoke in WebStorm and IntelliJ IDEA Ultimate, plus richer real-binary semantic smoke
evidence, are still explicit follow-up items rather than completed documentation claims.

## Development

Contributor-facing notes live in [docs/development.md](docs/development.md). The implementation
specs that shaped the current plugin live under [`specs/`](specs/README.md).
