# P2 Handoff: Design

## Objective
Turn the validated research into a JetBrains-native, implementation-ready design for the plugin.

## Required inputs
- `specs/RESEARCH.md`
- `specs/research-reports/*.md`
- local template under `.repos/intellij-platform-plugin-template`

## Context carried forward
- Primary targets are WebStorm and IntelliJ IDEA Ultimate.
- Unified PyCharm is technically LSP-capable since `2025.1`, but it is not a primary v1 target.
- Community Edition / open source IntelliJ builds and Android Studio remain unsupported.
- Baseline is `2025.3.x`.
- `2025.2.2` is only the earliest acceptable bootstrap baseline.
- Direct binary launch is required; IDE binary patching is forbidden.
- Core layer graph access comes from standard hover links; the VS Code local preview command remains a custom-request parity item or later milestone.
- Debug surfaces need adapted parity, not literal VS Code layout parity.
- The current research bias for v1 is a session-aware `Effect Dev Tools` tool window, with optional debugger affordances layered on later.
- JCEF is optional on day one; any advanced tracer panel needs a non-JCEF fallback.

## Steps
1. Review `specs/designs/*.md` and confirm they still match the research package.
2. Lock package layout, major services, settings contracts, and UI surfaces.
3. Confirm milestone boundaries between core LSP parity and Dev Tools parity.
4. Tighten explicit decisions for:
   - status widget
   - settings page
   - tool window layout
   - debugger bridge
   - tracer/JCEF fallback
5. Update `specs/DESIGN.md` as the canonical synthesis.
6. Ensure `specs/handoffs/P3_PLAN.md` reflects the final design.

## Deliverables
- updated `specs/designs/*.md` if needed
- updated `specs/DESIGN.md`
- validated `specs/handoffs/P3_PLAN.md`

## Acceptance criteria
- Architecture is explicit and decomposed into named subsystems.
- Public settings and service contracts are defined.
- The design names the fallback behavior for every known parity gap.

## Risks and gotchas
- Avoid overfitting the UI to VS Code layout.
- Avoid leaving binary management choices for implementation to decide later.
- Keep the local layer-mermaid preview explicitly blocked or deferred until a real transport exists.

## Unknowns to resolve if possible
- Final mix of tool-window surfaces versus XDebugger affordances
- Exact non-JCEF tracer fallback if advanced web UI is delayed
