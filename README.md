# Effect TSGO for JetBrains

<!-- Plugin description -->
JetBrains plugin for `@effect/tsgo` language-server support and Effect runtime Dev Tools.
It targets the WebStorm `2026.2` EAP platform line, launches `@effect/tsgo` directly with `--lsp --stdio`,
and ships core LSP integration plus local runtime Dev Tools. First-run binary setup is manual by default;
managed npm downloads are available when explicitly configured. Debugger surfaces include attach/setup
guidance, interactive live Effect snapshots, and opt-in Node.js instrumentation injection.
<!-- Plugin description end -->

## Overview

Effect TSGO for JetBrains brings `@effect/tsgo` into JetBrains IDEs and adds a local
`Effect Dev Tools` tool window for runtime clients, metrics, and tracer data.

The current plugin baseline is:

- Project-scoped Effect settings at `Settings | Tools | Effect`
- Direct binary launch through `@effect/tsgo --lsp --stdio`
- `MANUAL` binary mode by default, plus opt-in managed `LATEST` and `PINNED` modes
- An LSP widget for status, restart, logs, settings, and tool-window focus
- Runtime `Effect Dev Tools` tabs for `Clients`, `Metrics`, `Tracer`, and `Debug`
  surface

## Current Status

| Area | Status | Notes |
| --- | --- | --- |
| Core LSP wiring | Implemented | Direct binary launch, project settings, workspace/config passthrough, and widget actions are in place. |
| Editor features | Implemented | Diagnostics, code actions, completion, hover, inlay hints, symbols, and hover-based layer graph links are the intended supported surface; fuller real-IDE smoke evidence remains follow-up work. |
| Runtime Dev Tools | Implemented | Runtime server, client selection, metrics polling, tracer streaming, reset flows, and empty/error states are present. |
| Debugger surfaces | Adapted | The `Debug` tab can attach to the current session, render interactive Context/Span/Fiber/Breakpoint trees, reveal source locations, toggle pause-on-defects, interrupt fibers, and inject Node.js instrumentation. |
| Advanced tracer / JCEF | Adapted | The Swing tracer is the guaranteed baseline; a capability-gated JCEF tracer tab is shown when supported. |
| Local Mermaid graph action | Experimental | Editor/Tools action is capability-gated and requires a `tsgo` build that advertises the Effect execute-command bridge. |
| Supported-IDE manual editor smoke | Pending evidence | WebStorm sandbox boot and Plugin Verifier coverage are in place; recorded manual editor smoke remains follow-up work. |

## Supported IDEs

| IDE | Status | Notes |
| --- | --- | --- |
| WebStorm `2026.2` EAP | Primary target | `runIde` and verifier coverage target the pinned `262.6653.15` EAP build. |
| IntelliJ IDEA Ultimate `2026.2` EAP | Secondary target | Verifier coverage uses the latest available `262.*` IDEA EAP build. |
| Unified PyCharm `2025.1+` | Later target | Not a current compatibility promise. |
| IntelliJ IDEA Community Edition | Unsupported | JetBrains public LSP support is out of scope here. |
| Android Studio | Unsupported | Not a supported target for this plugin. |

## Quick Start

1. Build the plugin ZIP:

   ```bash
   ./gradlew build
   ```

   The Gradle build uses a Java 25 toolchain for the 2026.2 EAP classfile level.

2. Install the plugin from disk in a supported JetBrains IDE using the artifact in
   `build/distributions/`.
3. Open `Settings | Tools | Effect` and provide an executable native `tsgo` path in `MANUAL` mode.
   Managed `LATEST` and `PINNED` modes are available when you want the plugin to contact npm and
   download a platform package for you.
4. Open a supported TypeScript or JavaScript file: `.ts`, `.tsx`, `.cts`, `.mts`, `.js`, `.jsx`, `.cjs`, or `.mjs`.
5. Confirm the Effect LSP widget reaches `Running`, then open `Effect Dev Tools` if you want
   runtime metrics or tracer data.

The plugin launches `@effect/tsgo` directly. It does not patch JetBrains-managed or project-managed
TypeScript binaries.

## Documentation

- [Documentation hub](docs/README.md)
- [Getting started](docs/getting-started.md)
- [Usage guide](docs/usage.md)
- [VS Code and Zed parity matrix](docs/parity-matrix.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Development guide](docs/development.md)
- [Publishing guide](docs/publishing.md)
- [Reference sources](docs/reference-sources.md)
- [Privacy](PRIVACY.md)

## Verification

The repository currently uses these primary validation commands:

```bash
./gradlew build
./gradlew check
timeout 90s ./gradlew runIde
./gradlew verifyPlugin
```

The shipped artifact is intended for the WebStorm/IntelliJ Platform `262.*` build line. Full recorded
manual editor smoke plus richer real-binary semantic smoke evidence are explicit follow-up items rather
than completed documentation claims.

## Development

Contributor-facing notes live in [docs/development.md](docs/development.md). The implementation
specs that shaped the current plugin live under [`specs/`](specs/README.md).
