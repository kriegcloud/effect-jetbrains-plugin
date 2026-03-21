# Design: Product Scope

## Product statement
Build one JetBrains plugin, rooted under `dev.effect.intellij`, that combines:

1. First-class `@effect/tsgo` language-server support for TypeScript projects.
2. JetBrains-native Effect Dev Tools runtime surfaces that borrow capability ideas from the VS Code extension without cloning its layout.

## Primary targets
- WebStorm `2025.3.x`
- IntelliJ IDEA Ultimate `2025.3.x`

## Secondary compatibility target
- Unified PyCharm `2025.1+` only after the primary TypeScript workflow is stable

## Explicit non-targets
- IntelliJ IDEA Community Edition / open source builds
- Android Studio
- Any workflow that patches IDE-managed or project-managed TypeScript binaries

## Platform baseline
- Implementation baseline: `2025.3.x`
- Earliest acceptable bootstrap baseline: `2025.2.2`

## Product tiers and milestone boundaries

### Milestone 2 exit: core LSP parity
This is the first independently shippable slice.

- direct `@effect/tsgo --lsp --stdio` launch
- project-scoped settings for binary selection and server passthrough
- binary resolution modes: latest, pinned, manual path
- diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols
- hover-based layer graph links
- LSP status widget, notifications, and logs

### Milestone 3 exit: runtime Dev Tools parity
This is the intended v1 Dev Tools bar and must not depend on debugger integration.

- `Effect Dev Tools` tool window
- in-plugin runtime server lifecycle
- client list and active-client selection
- metrics surface
- tracer tree surface with details pane
- explicit empty states and error states

### Later milestone: debugger and advanced parity
These items are planned, but they are not on the critical path for the first usable release.

- debug-session bridge
- context / span stack / fibers / breakpoints surfaces
- run/debug instrumentation affordances
- optional JCEF-backed advanced tracer panel
- local layer-mermaid preview parity only after a real custom request transport exists

## Scope rules

### In scope for the core plugin
- binary management owned by the plugin
- project settings and validation
- JetBrains LSP integration
- session-aware Dev Tools tool window
- adapted debugger affordances later

### Out of scope for v1
- literal VS Code activity-bar or debug-sidebar parity
- custom LSP transport for local Mermaid preview
- mandatory JCEF dependency
- degraded Community Edition mode

## UX principles
- Prefer JetBrains-native concepts: status widget, configurable, tool window, actions.
- Keep LSP lifecycle and Dev Tools lifecycle related but independent.
- Make deferred parity visible through empty states or disabled actions, not hidden assumptions.
- Treat the tracer tree as the guaranteed baseline; anything richer is additive.
