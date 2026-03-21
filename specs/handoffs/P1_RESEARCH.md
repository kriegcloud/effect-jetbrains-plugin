# P1 Handoff: Research

## Objective
Validate and deepen the existing research package for the JetBrains plugin effort, keeping the split between core `@effect/tsgo` integration and Effect Dev Tools parity explicit.

## Required inputs
- `specs/RESEARCH_PLAN.md`
- local reference repos under `.repos/`
- any official JetBrains docs needed to confirm unsupported or version-sensitive facts

## Context carried forward
- `.repos/zed-effect-tsgo` is the clean reference for direct `@effect/tsgo` integration.
- `.repos/vscode-extension` is mainly Effect Dev Tools, not a pure `@effect/tsgo` plugin.
- JetBrains LSP support is commercial-IDE-only.
- Layer-mermaid parity is blocked on a non-standard request path.

## Steps
1. Re-audit the local references and confirm every major claim in `specs/RESEARCH.md`.
2. Fill any missing parity rows, especially around debugger-specific Dev Tools behavior.
3. Confirm any version-sensitive JetBrains facts from official docs.
4. Tighten any vague or speculative statements.
5. Update `specs/RESEARCH.md` and the topic reports if new facts are found.
6. Ensure `specs/handoffs/P2_DESIGN.md` still reflects the final research state.

## Deliverables
- updated `specs/research-reports/*.md` if needed
- updated `specs/RESEARCH.md`
- validated `specs/handoffs/P2_DESIGN.md`

## Acceptance criteria
- The product split is still explicit.
- Every parity claim has a named local source or an official JetBrains source behind it.
- Unsupported or blocked areas are documented honestly.

## Risks and gotchas
- Do not accidentally treat the VS Code extension as the `@effect/tsgo` plugin reference.
- Do not assume Community Edition support.
- Do not assume layer-mermaid works over LSP without explicit evidence.

## Unknowns to resolve if possible
- Exact JetBrains strategy for the debug-specific views
- Whether JCEF is required on day one or can remain a later parity item
