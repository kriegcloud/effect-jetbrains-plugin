# P3 Handoff: Plan

## Objective
Produce the final milestone-by-milestone execution plan for the plugin, aligned to the locked design decisions in `specs/DESIGN.md`.

## Required inputs
- `specs/DESIGN.md`
- `specs/designs/*.md`
- `specs/RESEARCH.md`

## Context carried forward
- The plan must use `2025.3.x` as the implementation baseline.
- The plugin must launch `@effect/tsgo` directly with `--lsp --stdio`.
- User-visible feature settings are project-scoped.
- The LSP widget is the only status-bar surface.
- `Effect Dev Tools` is the single tool-window home for runtime and later debugger surfaces.
- Core LSP parity must complete before Dev Tools runtime parity.
- Dev Tools runtime parity must complete before debugger/JCEF/custom-transport parity.
- Community Edition and Android Studio remain out of scope.

## Locked milestone boundaries
1. `Foundation`
   - template rename
   - baseline/dependency upgrade
   - package and service scaffold
   - fixture/test scaffold
2. `Core LSP Parity`
   - settings, binary lifecycle, LSP startup, status widget, and standard `@effect/tsgo` editor features
3. `Runtime Dev Tools Parity`
   - tool window, runtime server, clients, metrics, tracer tree, and user-facing empty/error states
4. `Debugger And Advanced Parity`
   - debug bridge, attach flow, instrumentation affordances, optional JCEF tracer, and any future custom transport work

## Steps
1. Review `specs/plans/*.md` and regroup them around the four locked milestones above.
2. Make Milestone 2 independently shippable, with explicit acceptance criteria and tests.
3. Keep Milestone 3 free of debugger and JCEF dependencies.
4. Place debugger integration, instrumentation work, and custom transport parity in Milestone 4 or later only.
5. Add test expectations and fixture needs to every milestone.
6. Update `specs/PLAN.md` as the canonical implementation plan.
7. Ensure `specs/handoffs/P4_IMPLEMENT.md` matches the final milestone structure.

## Deliverables
- updated `specs/plans/*.md`
- updated `specs/PLAN.md`
- validated `specs/handoffs/P4_IMPLEMENT.md`

## Acceptance criteria
- No critical implementation decision remains ambiguous.
- Milestones are ordered, independently verifiable, and match the locked design boundaries.
- Milestone 3 can ship without debugger parity or JCEF.
- Test expectations exist for each milestone.

## Risks and gotchas
- Do not blur runtime Dev Tools work with debugger-specific parity.
- Do not promise exact parity where the design explicitly chose adapted parity.
- Do not re-open binary management decisions during planning.
- Keep local layer-mermaid preview deferred unless a real transport proposal is added to the plan.

## Remaining planning unknowns
- Exact JetBrains APIs for debugger attach actions and run-configuration instrumentation
- Whether local layer-mermaid preview stays fully deferred beyond the first implementation plan
