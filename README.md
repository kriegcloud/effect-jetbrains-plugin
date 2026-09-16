# Effect TSGO for JetBrains

<!-- Plugin description -->
JetBrains plugin for `@effect/tsgo` language-server support and Effect runtime Dev Tools.
It targets the WebStorm `2026.2` stable and `2026.3` EAP platform lines, launches `@effect/tsgo` directly with `--lsp --stdio`,
and ships core LSP integration plus local runtime Dev Tools. First-run binary setup is manual by default;
managed npm downloads are available when explicitly configured. Debugger surfaces include attach/setup
guidance, best-effort snapshot trees for paused instrumented sessions, and opt-in Node.js instrumentation
injection.
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
| Debugger surfaces | Adapted | The `Debug` tab can attach to the current session, render best-effort Context/Span/Fiber/Breakpoint trees for paused instrumented sessions, reveal source locations, toggle pause-on-defects, interrupt fibers, and inject Node.js instrumentation. |
| Advanced tracer / JCEF | Adapted | The Swing tracer is the guaranteed baseline; a capability-gated JCEF tracer tab is shown when supported. |
| Local Mermaid graph action | Experimental | Editor/Tools action is capability-gated and requires a `tsgo` build that advertises the Effect execute-command bridge. |
| Supported-IDE manual editor smoke | Pending evidence | WebStorm sandbox boot and Plugin Verifier coverage are in place; recorded manual editor smoke remains follow-up work. |

## Supported IDEs

| IDE | Status | Notes |
| --- | --- | --- |
| WebStorm `2026.2` (stable) | Primary target | The compile target is the pinned `262.10315.144` stable build; `runIde` and verifier coverage run against it. |
| WebStorm `2026.3` EAP | Primary target | Plugin Verifier and the `runIdeVerifierWebStorm` sandbox target the pinned `263.4732.34` EAP build. |
| IntelliJ IDEA Ultimate `2026.3` EAP | Secondary target | Plugin Verifier targets the pinned `263.4732.28` IDEA EAP build. |
| Unified PyCharm `2025.1+` | Later target | Not a current compatibility promise. |
| IntelliJ IDEA Community Edition | Unsupported | JetBrains public LSP support is out of scope here. |
| Android Studio | Unsupported | Not a supported target for this plugin. |

EAP verifier pins must track the newest build because bundled libraries can change within a platform
line. WebStorm `263.4732.34` changed the lsp4j diagnostic-message API and exposed a highlighting
failure in 0.1.6; 0.1.7 adds a reflection-based accessor for both library versions. The
[refresh report](UPSTREAM_REFRESH_REPORT.md) records the actual verification results and remaining
evidence gaps for the build.

## Quick Start

1. Build the plugin ZIP:

   ```bash
   ./gradlew build
   ```

   The Gradle build uses a Java 25 toolchain for the 2026.2 platform classfile level.

2. Install the plugin from disk in a supported JetBrains IDE using the artifact in
   `build/distributions/`. For local WebStorm smoke testing, close WebStorm and run:

   ```bash
   scripts/install-local-webstorm-plugin.sh --product WebStorm2026.3
   ```

   The helper installs the latest built ZIP into the local JetBrains plugin directory, removes stale
   cached ZIPs, and clears a local Settings Sync disabled marker for `dev.effect.jetbrains`. Without
   `--product` it targets the config directory named by the JetBrains Toolbox WebStorm install
   (`product-info.json` → `dataDirectoryName`), falling back to `WebStorm2026.2`.
3. Restart the IDE when prompted so the LSP and tool-window extension points are registered at
   startup.
4. Open `Settings | Tools | Effect` and provide an executable native `tsgo` path in `MANUAL` mode.
   Managed `LATEST` and `PINNED` modes are available when you want the plugin to contact npm and
   download a platform package for you.
5. Open a supported TypeScript or JavaScript file: `.ts`, `.tsx`, `.cts`, `.mts`, `.js`, `.jsx`, `.cjs`, or `.mjs`.
6. Confirm the Effect LSP widget reaches `Running`, then open `Effect Dev Tools` if you want
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
timeout 90s ./gradlew runIdeVerifierWebStorm
./gradlew verifyPlugin
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/native/tsc
node scripts/verify-instrumentation.mjs
```

The shipped artifact is intended for the WebStorm/IntelliJ Platform `262.*` and `263.*` build lines
(`sinceBuild` `262`, `untilBuild` `263.*`). Recorded
real-binary LSP smoke exists for the checked-in fixtures; full manual IDE/editor smoke and broader
semantic coverage remain follow-up validation items.

The recorded native LSP smoke uses `@effect/tsgo@0.45.0`, `typescript@7.0.2`, and
`effect@4.0.0-rc.115`. New cases cover `obsoleteSchemaImport`, applied
`preferSucceedSomeOrNone` / `allOfMapToForEach` fixes, and opt-in `schemaSync`. The post-tag rules
`catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and `catchRefailToTapError` remain excluded
from published-binary coverage and directive completion.

The instrumentation verifier reuses the repository's
[`runtime smoke app`](src/test/testData/fixtures/runtime/smoke-app/) to check real Effect runtimes
before and after the rc.113 fiber-cache change. Its
[`SMOKE_CHECKLIST.md`](src/test/testData/fixtures/runtime/smoke-app/SMOKE_CHECKLIST.md) covers the
user-run editor, DevTools, and paused-debugger pass. See the [development guide](docs/development.md)
for both probe commands and fixture setup, and the [usage guide](docs/usage.md#current-tsgo-smoke-targets)
for upstream `diagnostics --list-files`, corrected rule metadata, and preset behavior. Oxlint and
tsgolint remain upstream-managed package contents only.

## Development

Contributor-facing notes live in [docs/development.md](docs/development.md). The implementation
specs that shaped the current plugin live under [`specs/`](specs/README.md).
