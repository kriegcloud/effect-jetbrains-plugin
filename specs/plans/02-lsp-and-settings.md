# Plan Slice: Core LSP Parity

## Objective
Ship the first independently usable release of the plugin: project-scoped settings, binary lifecycle, direct `@effect/tsgo --lsp --stdio` startup, and the standard editor features exposed through JetBrains LSP APIs.

## Locked scope
- Implement the project settings contract from `specs/DESIGN.md`.
- Keep all user-visible feature settings project-scoped.
- Support the three binary modes: `LATEST`, `PINNED`, and `MANUAL`.
- Launch `@effect/tsgo` directly with `--lsp --stdio`.
- Use the LSP widget as the only status-bar surface.
- Deliver the standard `@effect/tsgo` editor features:
  - diagnostics
  - code actions and workspace edits
  - completion
  - hover
  - inlay hints
  - document and workspace symbols
  - hover-based layer graph links
- Exclude runtime Dev Tools, debugger integration, JCEF, and custom transport work from this milestone.

## Ordered work
1. Implement `EffectProjectSettingsService`, the project-level configurable at `Settings | Tools | Effect`, and validation for binary and LSP fields.
2. Persist the full settings contract now so later Dev Tools and debugger fields do not require a migration, while only claiming Milestone 2 behavior for binary and LSP features.
3. Implement `EffectApplicationStateService` for machine-local binary cache preferences without moving any user-visible feature control out of project scope.
4. Implement `EffectBinaryService` with `LATEST`, `PINNED`, and `MANUAL` resolution, cache invalidation, executable validation, and actionable failure reporting.
5. Implement `EffectLspProjectService`, `LspServerSupportProvider`, and the server descriptor that launches `@effect/tsgo --lsp --stdio`.
6. Pass environment overrides, initialization options JSON, and workspace configuration JSON through the launch configuration.
7. Implement restart flow, startup diagnostics, log/output links, and the LSP widget states from `specs/DESIGN.md`.
8. Verify diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols, and hover-based layer graph links against fixtures.

## Test expectations
- Unit tests cover settings validation, resolved settings derivation, and binary resolution behavior for all three modes.
- Integration tests verify launch arguments, environment injection, initialization/workspace payloads, restart flow, and widget state transitions.
- Fixture-driven LSP tests verify diagnostics, code actions, completion, hover, inlay hints, and document/workspace symbols on `2025.3.x`.
- Manual smoke tests confirm the milestone works in WebStorm and IntelliJ IDEA Ultimate.

## Fixture needs
- A healthy TypeScript workspace that starts `@effect/tsgo` successfully.
- A failing workspace that exercises diagnostics and code actions.
- Fixtures that exercise hover output, Mermaid layer links, inlay hints, and symbol trees.
- Test binaries or harnesses for `LATEST`, `PINNED`, and `MANUAL` resolution paths.

## Exit criteria
- Opening a supported TypeScript file starts `@effect/tsgo` through the resolved binary path.
- All three binary modes are verified and produce actionable errors when misconfigured.
- The widget reports only LSP status and supports restart/settings/log actions.
- The standard `@effect/tsgo` editor features work on the locked baseline.
- This milestone is shippable without any Dev Tools runtime or debugger surface.
