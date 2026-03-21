# P4 Handoff: Implement

## Objective
Implement the plugin according to `specs/PLAN.md`, in milestone order, with verification after each milestone.

## Required inputs
- `specs/PLAN.md`
- `specs/DESIGN.md`
- `specs/RESEARCH.md`
- local template and reference repos

## Context carried forward
- The implementation baseline is `2025.3.x`.
- The critical path is core `@effect/tsgo` LSP parity.
- The plugin must launch `@effect/tsgo` directly with `--lsp --stdio`.
- All user-visible feature settings are project-scoped.
- The LSP widget is the only status-bar surface.
- `Effect Dev Tools` is the single tool-window home for runtime and later debugger surfaces.
- Runtime Dev Tools parity must ship before any debugger, JCEF, or custom-transport work begins.
- Debug-specific features require adapted JetBrains UX rather than literal VS Code layout parity.

## Steps
1. Execute Milestone 1 `Foundation` and verify the renamed plugin scaffold builds, runs, and loads fixtures.
2. Execute Milestone 2 `Core LSP Parity` and verify it as the first independently shippable release.
3. Execute Milestone 3 `Runtime Dev Tools Parity` and keep it strictly limited to runtime server, clients, metrics, tracer, and explicit empty/error states.
4. Execute Milestone 4 `Debugger And Advanced Parity` only after Milestone 3 is stable; keep JCEF optional and keep custom transport work gated on a real design.
5. Document any intentional deferral or adapted-parity outcome in code comments or maintainer docs when encountered.
6. Update docs if implementation reveals a real mismatch with the locked design or plan.
7. Prepare `specs/handoffs/P5_REVIEW.md` with actual implementation findings and remaining gaps.

## Deliverables
- plugin source changes
- passing build/test baseline
- updated docs where implementation reality changed
- completed `specs/handoffs/P5_REVIEW.md`

## Acceptance criteria
- Each milestone ends in a buildable, testable state.
- Milestone 2 core language-server features work before Dev Tools expansion begins.
- Milestone 3 ships without debugger parity, JCEF, or local Mermaid preview dependencies.
- Any intentionally deferred parity area is documented.

## Risks and gotchas
- Avoid hidden scope creep from VS Code-only behaviors.
- Do not silently hardcode one binary mode; all three modes are part of the spec.
- Do not blur runtime Dev Tools work with debugger-specific parity.
- Be careful with platform-specific binary handling and path validation.
- Keep local layer-mermaid preview deferred unless a real transport proposal is implemented.

## Unknowns to resolve in implementation
- Exact JetBrains debugger extension points needed for context/span/fiber views
- Exact JetBrains APIs for attach actions and run-configuration instrumentation
- Whether local layer-mermaid preview remains deferred beyond the first implementation pass
