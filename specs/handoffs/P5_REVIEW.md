# P5 Handoff: Review

## Objective
Capture the actual implementation state after the P4 buildout, with the most important remaining risks and parity gaps called out for review and test follow-up.

## Required inputs
- implemented plugin code
- `specs/PLAN.md`
- `specs/DESIGN.md`
- `specs/RESEARCH.md`

## Context carried forward
- Full parity was the target, but some areas were explicitly allowed to be adapted or deferred.
- Core `@effect/tsgo` parity matters more than matching VS Code layout.
- Review must distinguish bugs from deliberate scope cuts.

## Verification completed

- `./gradlew test --tests 'dev.effect.intellij.devtools.EffectDevToolsServiceTest' --tests 'dev.effect.intellij.lsp.EffectLspStatusOwnershipTest'`
- `./gradlew test`
- `./gradlew check build`
- `timeout 90s ./gradlew runIde`
  - The WebStorm `2025.3.3` sandbox booted successfully and the command timed out intentionally after startup.
- `./gradlew verifyPlugin`
  - The shipped artifact is now Marketplace-valid as `dev.effect.jetbrains` / `Effect TSGO`.
  - Plugin Verifier reported the plugin compatible against `WS-253.32098.39` and `WS-261.22158.185`.
  - Verifier follow-up risk remains limited to `1` scheduled-for-removal API usage, `8` deprecated API usages, and `6` experimental API usages.

## Milestone status

### Milestone 1: Foundation

- Complete.
- Kotlin packages remain rooted under `dev.effect.intellij`, while the shipped plugin metadata was adjusted to the Marketplace-safe `dev.effect.jetbrains` / `Effect TSGO`.
- The plugin targets the `2025.3.x` platform line, registers the planned services, and includes fixture directories for `lsp`, `devtools`, and `debug`.

### Milestone 2: Core LSP Parity

- Implemented enough to remain the first shippable baseline, but not yet proven enough to claim full Milestone 2 parity.
- Project-scoped settings, validation, managed/manual binary resolution, direct launch wiring, workspace configuration, initialization options, and LSP widget actions are in place.
- The LSP widget now tracks only language-server lifecycle and LSP-relevant restart needs; binary resolution and unrelated settings no longer mark the widget `RUNNING` or `RESTART_REQUIRED`.
- The remaining review gap is evidence: there is still no completed end-to-end automated or recorded manual smoke pass proving diagnostics, code actions, completion, hover, inlay hints, symbols, and hover-based layer links against a real `@effect/tsgo` binary on supported IDEs.

### Milestone 3: Runtime Dev Tools Parity

- Implemented as the runtime-only baseline and now aligned with the local Effect Dev Tools reference protocol.
- The `Effect Dev Tools` tool window, local WebSocket runtime server, client tracking, metrics polling, tracer trees, reset flows, and explicit empty/error states are present.
- Runtime metrics now parse the real Effect v4 shape from the local reference implementation: `_tag`, `name`, `tags`, and the concrete metric-state payloads. Outbound `MetricsRequest` and `Pong` frames are newline-delimited and covered by protocol-level tests.
- Tracer handling remains aligned with the same transport model: metrics are polled, while tracer data is streamed from `Span` and `SpanEvent` messages.

### Milestone 4: Debugger And Advanced Parity

- Partially implemented and intentionally adapted.
- The tool window still exposes `Debug` tabs and an attach flow tied to the active JetBrains debug session.
- Misleading instrumentation controls and injection claims were removed from the settings surface. The current debugger experience is guidance-only and explicitly states that automatic instrumentation injection and live Effect debug snapshots are not implemented yet.
- Live context/span/fiber/breakpoint snapshots, optional JCEF tracer UI, and any local Mermaid preview transport remain deferred and are represented as explicit setup/guidance states rather than fabricated parity.

## Findings

1. High: core LSP parity is still not proven end-to-end on supported IDEs.
   The real-binary verifier script can initialize the native `@effect/tsgo` executable and observe the expected advertised capabilities, but its first `textDocument/hover` request times out in this environment. There is still no completed automated fixture proof or recorded manual smoke result for diagnostics, code actions, completion, hover, inlay hints, symbols, or hover-based layer links.

2. Medium: supported-IDE manual smoke verification is still incomplete.
   `runIde` proves the WebStorm `2025.3.3` sandbox boots, and Plugin Verifier covers recommended WebStorm builds, but there is still no recorded manual editor smoke run for WebStorm or IntelliJ IDEA Ultimate. Support should remain documented as pending manual verification rather than fully proven parity.

3. Medium: debugger parity is intentionally guidance-first rather than snapshot-first.
   `src/main/kotlin/dev/effect/intellij/debug/EffectDebugBridgeService.kt` can attach to the active JetBrains debug session and report setup state, but it still does not surface live Effect runtime snapshots for `Context`, `Span Stack`, `Fibers`, or `Breakpoints`. This remains an honest deferral, not a hidden bug.

4. Low: verifier follow-up work remains after compatibility success.
   Plugin Verifier now passes, but it reports `1` scheduled-for-removal API usage, `8` deprecated API usages, and `6` experimental API usages. Those warnings do not block the current remediation pass, but they are worth tracking before a long-lived release line.

## Resolved In P7

- Runtime metrics parsing now matches the local Effect Dev Tools reference protocol instead of the old synthetic test shape.
- Runtime outbound frames now use valid newline-delimited JSON, with tests asserting the exact `MetricsRequest` and `Pong` messages sent over the socket.
- LSP widget ownership is now language-server-specific; binary resolution and unrelated settings saves no longer fake `RUNNING` or blanket `RESTART_REQUIRED` states.
- The misleading debugger injection controls were removed from the user-visible settings surface, and the attach flow now states clearly that instrumentation injection and live debug snapshots are deferred.

## Intentional deferrals

- Live debugger snapshot extraction for `Context`, `Span Stack`, `Fibers`, and `Breakpoints`
- Optional JCEF tracer UI beyond capability detection
- Local layer-Mermaid preview transport beyond the existing hover-link path
- Any support claim for IntelliJ IDEA Community Edition, Android Studio, or un-smoked commercial IDE variants

## Recommended P6 test focus

1. Record real editor smoke tests in WebStorm and IntelliJ IDEA Ultimate against a native `@effect/tsgo` executable, not the npm wrapper script.
2. Prove core LSP editor behavior on the fixture workspaces: diagnostics, code actions, completion, hover, inlay hints, symbols, and hover-based layer graph links.
3. Exercise all three binary modes with success and failure cases in live IDE flows, not only resolver/unit coverage.
4. Keep verifying the adapted debug surfaces only for what they currently promise: attach flow, setup guidance, and non-fabricated empty states.
