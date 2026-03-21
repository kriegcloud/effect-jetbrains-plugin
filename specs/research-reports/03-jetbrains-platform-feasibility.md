# Research Report: JetBrains Platform Feasibility

## Summary
Official JetBrains docs are strong enough to support a direct `@effect/tsgo` plugin, but they also make the constraints sharper than the current handoff language. The plugin must target JetBrains IDEs that expose the public LSP API, should build against `2025.3.x` for the fullest LSP surface, and can keep JCEF as an optional later parity item as long as a browser-less fallback exists.

## Official constraints confirmed

### IDE support
JetBrains states that the public LSP API is not available in:

- IntelliJ IDEA open source builds
- Android Studio from Google

The same doc also says the public LSP API is available in IntelliJ IDEA, WebStorm, PhpStorm, PyCharm, DataSpell, RubyMine, CLion, DataGrip, GoLand, Rider, and RustRover, and adds that unified PyCharm without Pro subscription is supported since `2025.1`.

For this project, the practical primary targets still stay:

- WebStorm
- IntelliJ IDEA Ultimate

That keeps the product scope narrow without repeating the older, now slightly stale, "commercial only" wording as if it were universally true in `2026`.

### Required plugin dependencies
The plugin manifest must depend on:

- `com.intellij.modules.lsp`
- `com.intellij.modules.ultimate`

`com.intellij.modules.platform` remains the normal base dependency for any platform plugin.

### Minimum platform
- LSP plugin development baseline: `2023.2+`
- Earliest baseline that can support Effect inlay hints: `2025.2.2`
- Recommended baseline for full planned parity: `2025.3.x`

## Relevant LSP feature timeline

| Platform release | Relevant feature |
| --- | --- |
| `2023.2` | stdio transport, diagnostics, quick fixes, completion, go to declaration |
| `2023.3` | formatting, intention actions, quick documentation |
| `2024.1` | socket transport, execute command, apply workspace edit, show document |
| `2024.2` | find usages, completion resolve, code action resolve, semantic tokens |
| `2025.1` | document link, pull diagnostics |
| `2025.2.2` | inlay hints, folding range |
| `2025.3` | workspace symbols, structure/document symbols integration, breadcrumbs, sticky lines, parameter info, server progress |

## JCEF findings

The official JCEF docs are more permissive than the existing handoff language:

- JetBrains recommends Swing first and says to use JCEF when HTML or richer web-based UI is genuinely needed.
- Plugins must guard JCEF usage with `JBCefApp.isSupported()`.
- The docs explicitly show an optional fallback branch when JCEF is unavailable.
- JCEF can be unavailable when the IDE runs on an alternative JDK that does not bundle JCEF or when the JCEF version is incompatible with the IDE.

### Practical conclusion
JCEF is not a day-one requirement for the plugin. A tracer webview can remain a later parity item if the plugin ships a browser-less fallback such as a tracer tree or simpler Swing-based panel.

## Debugger and run-configuration findings

- The official Run Configurations docs confirm that run configurations are persistent and expose environment variables, program arguments, and other process settings.
- The official Tool Window docs explicitly position tool windows as the place to surface information used while running and debugging applications, with support for tabs, toolbars, and tree-oriented layouts.

### Inference from sources
JetBrains clearly has the primitives needed to host session-bound Dev Tools UI, but the docs do not describe a literal equivalent of VS Code's custom debug view container. The lowest-risk v1 strategy is therefore:

- a dedicated session-aware `Effect Dev Tools` tool window for context/span/fibers/breakpoints
- optional debugger actions or session affordances layered on top later

Exact XDebugger placement remains a design-stage decision, but the research no longer needs to treat it as an all-or-nothing unknown.

## Recommended baseline

### Chosen baseline for implementation
Target `2025.3.x` for implementation planning.

### Why
- Maximizes LSP surface for `@effect/tsgo`.
- Includes document-symbol-driven IDE integrations such as structure/breadcrumbs.
- Includes inlay hints support already needed for Effect-specific hints.
- Leaves fewer parity gaps versus the server feature set.

### Practical note
The local JetBrains template is pinned to `2025.2.6.1`. That is acceptable for early scaffolding, but implementation should upgrade to a `2025.3.x` baseline before parity work starts.

## Recommended IDE targets

### Primary tested IDEs
- WebStorm
- IntelliJ IDEA Ultimate

### Secondary compatibility targets
- Other LSP-capable JetBrains IDEs, including unified PyCharm, only after TypeScript workflow validation

### Explicit non-targets
- IntelliJ IDEA Community Edition
- Android Studio

## JetBrains primitive mapping

| Need | JetBrains primitive |
| --- | --- |
| Start language server | `LspServerSupportProvider` + `ProjectWideLspServerDescriptor` |
| Custom status | LSP status widget via `createLspServerWidgetItem()` |
| User configuration | `Configurable` + persistent state service |
| Command surfaces | actions and tool window toolbar actions |
| Side panels | tool windows and content tabs |
| Embedded tracer UI | JCEF panel with fallback if unavailable |
| Observability | IDE log + notifications + optional log/output surface |
| Debug-specific views | Session-aware tool window first, optional XDebugger augmentation later |

## Important parity gaps

### Local layer-mermaid preview
Core layer graph links already fit standard hover. The exact VS Code local preview command does not. JetBrains can only reach that extra parity if the plugin and server agree on a custom LSP request/notification path.

### Debug sidebars
VS Code offers dedicated debug view containers and context menus. JetBrains will need adapted UX, most likely a dedicated `Effect Dev Tools` tool window first and optional debugger actions later.

### Community edition support
Not possible if the plugin depends on the public LSP API.

## Recommendation
Build the product for LSP-capable JetBrains IDEs, with first-class support for WebStorm and IntelliJ IDEA Ultimate. Declare Community Edition and Android Studio as unsupported rather than attempting a degraded mode, and treat unified PyCharm as a later compatibility target rather than a primary v1 promise.

## Official checkpoints
- JetBrains `Language Server Protocol (LSP)` docs: https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html
- JetBrains `Embedded Browser (JCEF)` docs: https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
- JetBrains `Tool Window` docs: https://plugins.jetbrains.com/docs/intellij/tool-window.html
- JetBrains `Run Configurations` docs: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- JetBrains `Dependencies Extension` docs: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
