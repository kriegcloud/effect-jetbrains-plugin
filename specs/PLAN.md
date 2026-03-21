# PLAN: Effect Tsgo JetBrains Plugin

## Summary
Implementation proceeds in four locked milestones:

1. `Foundation`
2. `Core LSP Parity`
3. `Runtime Dev Tools Parity`
4. `Debugger And Advanced Parity`

The release-critical path is Milestone 2. Milestone 3 is the first Dev Tools release and must stay free of debugger, JCEF, and custom-transport dependencies. Milestone 4 is the only place where debugger integration, instrumentation work, optional JCEF, and future custom transport parity belong.

## Locked execution rules
- Baseline the implementation on `2025.3.x`.
- Launch `@effect/tsgo` directly with `--lsp --stdio`.
- Keep all user-visible feature settings project-scoped.
- Use the LSP widget as the only status-bar surface.
- Use one `Effect Dev Tools` tool window for runtime and later debugger surfaces.
- Finish core LSP parity before runtime Dev Tools parity.
- Finish runtime Dev Tools parity before debugger, JCEF, or custom-transport work.
- Keep Community Edition and Android Studio out of scope.
- Keep local layer-mermaid preview deferred unless a real transport proposal is implemented and tested.

## Implementation plan

### Milestone 1: Foundation
- Outcome: a real `dev.effect.intellij` plugin scaffold that can support fixture-driven implementation.
- Key work:
  - rename the template into the final plugin identity
  - upgrade build and platform targets to `2025.3.x`
  - add the required plugin dependencies
  - create the package and service scaffold from `specs/DESIGN.md`
  - add logging, notification, and constants helpers
  - add `src/test/testData/fixtures/{lsp,devtools,debug}` with a minimal TypeScript sample workspace
- Verification:
  - `build`, `check`, and `runIde` succeed
  - service registration smoke tests pass
  - fixture loading works from the new layout

### Milestone 2: Core LSP Parity
- Outcome: the first independently shippable release of the plugin.
- Key work:
  - implement the project-scoped settings contract and validation
  - implement `LATEST`, `PINNED`, and `MANUAL` binary resolution
  - launch `@effect/tsgo --lsp --stdio` through JetBrains LSP APIs
  - pass environment overrides, initialization options, and workspace configuration
  - implement the LSP widget states, restart flow, startup diagnostics, and log actions
  - verify diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols, and hover-based layer graph links
- Verification:
  - all three binary modes work and fail clearly when misconfigured
  - supported TypeScript files start the server and show standard `@effect/tsgo` editor features
  - widget state transitions and restart behavior are covered by tests
  - manual smoke tests pass in WebStorm and IntelliJ IDEA Ultimate
- Fixture needs:
  - healthy and failing TypeScript workspaces
  - hover, Mermaid link, inlay hint, and symbol fixtures
  - resolver fixtures or harnesses for latest, pinned, and manual binaries

### Milestone 3: Runtime Dev Tools Parity
- Outcome: a shippable runtime-only `Effect Dev Tools` release layered on top of Milestone 2.
- Key work:
  - create the `Effect Dev Tools` tool window and runtime toolbar actions
  - implement the project-local runtime server lifecycle
  - implement clients, active-client selection, metrics, and Swing tracer views
  - add explicit empty states and runtime error handling
- Verification:
  - Milestone 2 LSP behavior does not regress
  - runtime tabs work without any debugger integration
  - multiple-client and reset flows are tested
  - the milestone can ship without JCEF or local Mermaid preview
- Fixture needs:
  - mock runtime client harness
  - metrics and tracer snapshot fixtures
  - zero-client and runtime-failure fixtures

### Milestone 4: Debugger And Advanced Parity
- Outcome: adapted debugger parity and optional advanced surfaces, without making them prerequisites for Milestones 2 or 3.
- Key work:
  - implement the debug bridge and attach flow
  - add `Debug` tabs for `Context`, `Span Stack`, `Fibers`, and `Breakpoints`
  - add project-scoped instrumentation affordances for supported debug configuration types
  - add an optional JCEF tracer panel guarded by capability checks
  - implement local layer-mermaid preview only if a concrete custom transport is designed and validated
- Verification:
  - debug tabs show real attached-session data or explicit attach/setup guidance
  - runtime tabs still work without a debugger
  - JCEF is optional and the Swing tracer remains the guaranteed baseline
  - any custom transport work has protocol and failure-mode coverage
- Fixture needs:
  - instrumented and non-instrumented debug-session fixtures
  - snapshot fixtures for context, span stack, fibers, and breakpoints
  - JCEF capability-toggle coverage if the advanced tracer is implemented

## Test plan

- Every milestone ends in a buildable, testable state.
- Milestone 2 is the first release candidate and must have fixture-driven LSP coverage plus supported-IDE smoke tests.
- Milestone 3 adds runtime fixtures and mock-client integration coverage, but must remain shippable without debugger or JCEF support.
- Milestone 4 adds debugger fixtures, attach-flow validation, and optional JCEF or transport coverage only when those features are actually implemented.
- Packaging and release verification always includes build, tests, plugin verifier, and manual smoke tests in WebStorm and IntelliJ IDEA Ultimate.

## Assumptions
- Commercial IDE support is acceptable.
- Direct binary launch is the approved runtime model.
- Runtime Dev Tools parity is adapted parity, not a promise to clone VS Code layout.
- Layer-mermaid local preview may remain deferred beyond the first implementation pass if transport design is still unresolved.
