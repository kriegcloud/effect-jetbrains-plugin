# Specs README

This directory is the operating manual for the JetBrains plugin effort.

## Reading order
1. `OVERVIEW.md`
2. `RESEARCH.md`
3. `DESIGN.md`
4. `PLAN.md`
5. `handoffs/P1_RESEARCH.md` through `handoffs/P6_TEST.md`

## Directory layout
- `RESEARCH_PLAN.md`
  - how research should be performed and reviewed
- `research-reports/`
  - topic reports that feed `RESEARCH.md`
- `designs/`
  - design slices that feed `DESIGN.md`
- `plans/`
  - implementation plan slices that feed `PLAN.md`
- `handoffs/`
  - prompts/instructions for orchestrator agents to execute each phase

## Phase model
- `P1 RESEARCH`
  - discover facts, map parity, and lock assumptions
- `P2 DESIGN`
  - define architecture, UX, and public interfaces
- `P3 PLAN`
  - convert the design into implementation slices and acceptance criteria
- `P4 IMPLEMENT`
  - build the plugin in milestone order
- `P5 REVIEW`
  - audit parity, regressions, packaging, docs, and code quality
- `P6 TEST`
  - validate behavior across fixtures, IDEs, and operating systems

## How to use this spec set
- Use the handoff file for the current phase as the direct orchestrator prompt.
- Treat `RESEARCH.md`, `DESIGN.md`, and `PLAN.md` as the canonical synthesized documents.
- Carry forward gotchas and unknowns from one phase to the next rather than rediscovering them.
