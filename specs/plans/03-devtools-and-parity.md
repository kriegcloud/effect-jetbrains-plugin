# Plan Slice: Runtime Dev Tools Parity

## Objective
Add the first JetBrains-native Effect Dev Tools release on top of the shippable LSP plugin, limited to runtime functionality that works without debugger APIs, JCEF, or any custom transport work.

## Locked scope
- Create one tool window named `Effect Dev Tools`.
- Implement runtime server lifecycle and shared runtime state in `EffectDevToolsService`.
- Ship only the runtime tabs:
  - `Clients`
  - `Metrics`
  - `Tracer`
- Support active-client selection and reset actions.
- Provide explicit empty states and runtime error states.
- Keep debugger surfaces, instrumentation affordances, JCEF, and local Mermaid preview out of this milestone.

## Ordered work
1. Build the `Effect Dev Tools` tool window shell and shared toolbar actions for runtime start/stop, restart, open settings, active-client selection, reset metrics, and reset tracer.
2. Implement the project-local runtime server lifecycle, status model, and last-error reporting in `EffectDevToolsService`.
3. Implement connected-client tracking and active-client selection without coupling runtime startup to the LSP lifecycle.
4. Build the `Clients` tab with server summary, connected-client list, and selected-client details.
5. Build the `Metrics` tab with polling, summary rendering, and selected-metric details.
6. Build the guaranteed Swing `Tracer` tab with span tree, selected-span details, and source reveal when location data exists.
7. Add explicit empty and error states for no clients, failed runtime startup, malformed runtime data, and stale client selection.

## Test expectations
- Unit tests cover runtime server start/stop/restart, active-client selection, and reset actions.
- Fixture-driven UI or model tests cover clients, metrics, and tracer rendering.
- Integration tests exercise mock client connect/disconnect flows, multiple-client selection, and runtime error recovery.
- Manual smoke tests confirm the tool window is usable without debugger integration or JCEF support.

## Fixture needs
- A mock runtime client harness that can connect, disconnect, and expose multiple clients.
- Metrics snapshot fixtures for empty, populated, and reset states.
- Tracer snapshot fixtures for tree rendering, detail panes, and source locations.
- Error fixtures for port bind failures, malformed payloads, and zero-client states.

## Exit criteria
- Milestone 2 behavior remains intact and does not regress.
- `Effect Dev Tools` ships with runtime-only surfaces: `Clients`, `Metrics`, and `Tracer`.
- Runtime views update correctly from connected clients and degrade clearly when data is missing or invalid.
- The milestone is shippable without debugger parity, JCEF, or local Mermaid preview support.
