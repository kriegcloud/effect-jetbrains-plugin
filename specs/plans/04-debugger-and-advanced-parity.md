# Plan Slice: Debugger And Advanced Parity

## Objective
Finish the higher-risk adapted-parity work only after runtime Dev Tools parity is stable: debugger bridge integration, instrumentation affordances, optional JCEF enhancements, and any future custom transport work.

## Locked scope
- Implement the debug bridge and attach flow in `EffectDebugBridgeService`.
- Add the `Debug` tab group inside `Effect Dev Tools` for:
  - `Context`
  - `Span Stack`
  - `Fibers`
  - `Breakpoints`
- Add project-scoped instrumentation affordances for confirmed run/debug configuration types.
- Add an optional JCEF tracer panel only when supported, while preserving the Swing tracer as the guaranteed fallback.
- Keep local layer-mermaid preview deferred unless a real custom transport proposal is designed, implemented, and tested.

## Ordered work
1. Validate the exact JetBrains `2025.3.x` APIs needed for attach actions, session observation, and run/debug instrumentation hooks before committing implementation details.
2. Implement `EffectDebugBridgeService` with attach, detach, session-state tracking, and snapshot refresh flow.
3. Build the `Debug` tab group in `Effect Dev Tools` and render real context, span stack, fiber, and breakpoint data when an instrumented session is attached.
4. Add attach and focus actions that bring users to `Effect Dev Tools` instead of cloning the VS Code debug sidebar layout.
5. Implement project-scoped instrumentation affordances only for the debug configuration types that can be safely supported on the locked baseline.
6. Add the optional JCEF tracer panel behind capability checks, while keeping the Swing `Tracer` tab functional on every supported IDE.
7. Evaluate local layer-mermaid preview only if a concrete custom request or transport design exists; otherwise record it as intentionally deferred beyond this milestone.

## Test expectations
- Unit tests cover debug bridge state transitions, attach and detach flow, and snapshot transformation logic.
- Fixture-driven tests cover debug context, span stack, fibers, breakpoints, and missing-instrumentation empty states.
- Integration or manual tests exercise at least one instrumented session and one non-instrumented session on the locked IDE baseline.
- If JCEF support is implemented, conditional tests cover both supported and unsupported environments.
- If custom transport work begins, protocol compatibility and failure-mode tests are mandatory.

## Fixture needs
- A paused, instrumented sample application and workspace for debugger coverage.
- Snapshot fixtures for context, span stack, fibers, and breakpoint trees.
- A non-instrumented debug-session fixture for attach guidance and empty-state coverage.
- A capability-toggle harness for JCEF-supported versus JCEF-unsupported environments, if the advanced tracer is implemented.
- Transport fixtures only if local layer-mermaid preview work is actually started.

## Exit criteria
- Runtime Dev Tools remain fully usable without attaching a debugger.
- Debug surfaces show either real attached-session data or an explicit setup and attach state.
- JCEF remains optional and never replaces the Swing tracer baseline.
- Local layer-mermaid preview ships only if a real transport exists; otherwise it stays deferred and documented as such.
