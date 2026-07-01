# VS Code And Zed Parity Matrix

Last checked: June 30, 2026 (against `@effect/tsgo@0.15.0` and `effect@4.0.0-beta.92`).

This matrix tracks behavioral parity, not literal UI cloning. VS Code is the runtime Dev Tools
reference. Zed is the direct `@effect/tsgo` language-server launch reference.

| Capability | Upstream reference | JetBrains status | Evidence |
| --- | --- | --- | --- |
| Native `tsgo --lsp --stdio` launch | Zed | Implemented | `EffectLspProjectService`, real-binary smoke |
| Manual native binary path | Zed | Implemented | Settings validation and manual-mode tests |
| Managed latest/pinned npm package install | Zed | Implemented; JetBrains also validates npm integrity | Binary service tests |
| Platform package resolution | Zed | Implemented | Linux/macOS/Windows package naming tests |
| Initialization options passthrough | Zed | Implemented | LSP descriptor tests |
| Workspace configuration passthrough | Zed | Implemented | LSP descriptor tests |
| Typed common `@effect/tsgo` settings | Zed/tsgo | Implemented with raw JSON escape hatch | Settings merge tests |
| Diagnostics/code actions/completion/hover/symbols | tsgo | Implemented through LSP | Real-binary verifier |
| Hover Mermaid links | tsgo | Implemented through LSP hover | Real-binary verifier |
| Local Layer Mermaid preview | tsgo hover link | Implemented; decodes the `mermaid.live` `pako:` link from Layer hover into a local `.mmd` (requires `noExternal=false`). Execute-command probe kept as a forward-compatible path (no such command ships in 0.15.0). | `EffectLayerMermaidService` + decoder test; real-binary hover smoke |
| Runtime Dev Tools server | VS Code | Implemented | Dev Tools service tests |
| Runtime clients and active selection | VS Code | Implemented | Dev Tools service tests |
| Metrics polling/reset | VS Code | Implemented | Dev Tools service tests |
| Tracer tree/reset | VS Code | Adapted; JetBrains-native tree | Dev Tools service tests |
| Advanced tracer webview | VS Code | Adapted; optional JCEF visualization | Capability-gated UI |
| Debug Context view | VS Code | Adapted; interactive JetBrains tree | Debug tree model tests |
| Debug Span Stack view | VS Code | Adapted; interactive tree plus ignore list | Debug tree model tests |
| Debug Fibers view | VS Code | Adapted; interactive tree plus fiber interrupt metadata | Debug tree model tests |
| Debug Breakpoints view | VS Code | Adapted; pause-on-defects and reveal values | Debug tree model tests |
| Source reveal from debug snapshots | VS Code | Adapted | Debug tree model and tool-window action |
| Copy value affordance | VS Code | Adapted | Tool-window tree action |
| Node instrumentation injection | VS Code | Adapted; opt-in JetBrains allowlist plus wildcard | Settings and run-configuration extension |
| Attach debug session as Dev Tools client | VS Code | Intentionally separate | JetBrains keeps runtime clients and paused debug snapshots separate |

## Release Evidence Checklist

Before publishing parity claims, record:

- IDE build and OS.
- Binary mode and `@effect/tsgo` package version.
- Real-binary LSP verifier output.
- Runtime Dev Tools smoke with a connected Effect app.
- Paused Node debug-session smoke with instrumentation enabled.
- Mermaid command capability result from LSP initialization.
