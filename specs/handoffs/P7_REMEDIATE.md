# P7 Handoff: Remediate

## Objective
Fix all actionable issues identified in review, close the verification gaps that block honest parity claims, and keep deliberate milestone deferrals clearly separated from bugs.

## Required inputs
- implemented plugin code
- `specs/handoffs/P5_REVIEW.md`
- `specs/PLAN.md`
- `specs/DESIGN.md`
- `specs/RESEARCH.md`
- local reference repos, especially `.repos/vscode-extension`, `.repos/effect-tsgo`, and `.repos/zed-effect-tsgo`

## Context carried forward
- Core `@effect/tsgo` parity remains the release-critical path.
- Runtime Dev Tools parity must reflect the real Effect v4 protocol, not a synthetic test-only shape.
- The LSP widget must remain language-server-specific.
- Live debugger snapshots, optional JCEF tracer UI, and local layer-Mermaid preview transport are still allowed to remain deferred unless they are explicitly implemented and tested.
- The goal of this pass is to fix bugs, misleading surfaces, and missing evidence, not to erase every intentional scope cut.

## Issues to remediate

### 1. Runtime metrics protocol mismatch
- The Kotlin runtime parser currently expects synthetic fields such as `id`, `type`, `attributes`, and `state.incremental`.
- The local Effect Dev Tools reference emits `_tag`, `name`, `tags`, and the real metric state shapes.
- Update the runtime models, parser, summaries, and UI rendering to match the local reference protocol exactly.
- Use `.repos/vscode-extension/src/instrumentation/encoders.ts` and `.repos/vscode-extension/src/instrumentation/instrumentation.ts` as the source of truth for runtime payload shape.

### 2. Runtime outbound frame formatting bug
- The runtime server currently sends `MetricsRequest` and `Pong` with a literal `\n` sequence in the payload.
- Fix the framing so clients receive valid newline-delimited JSON or whatever exact framing the chosen runtime transport requires.
- Add tests that assert the exact outbound messages sent over the socket.

### 3. LSP widget status ownership bug
- Binary resolution currently marks the widget `RUNNING`, even before the language server lifecycle reaches a running state.
- Project settings updates currently mark `RESTART_REQUIRED` for every change, including non-LSP settings.
- Refactor status ownership so the widget tracks only language-server lifecycle and restart needs.
- If a setting does not affect the LSP process, it must not drive LSP widget state.

### 4. Misleading debugger instrumentation surface
- The settings UI exposes debugger injection controls and the attach flow reports injection state, but there is no real implementation that mutates JetBrains debug configurations or injects `NODE_OPTIONS`.
- Preferred fix: implement real instrumentation support for the supported JetBrains debug configuration types.
- Acceptable fallback if full implementation is too risky in this pass: remove, disable, or clearly mark the controls and attach guidance as not yet implemented.
- Do not leave a user-visible capability claim without a working execution path.

### 5. Core LSP verification gap
- Milestone 2 still lacks fixture-driven or recorded end-to-end proof for diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols, and hover-based layer graph links.
- Add automated coverage where feasible and complete manual smoke evidence against a real `@effect/tsgo` binary in both WebStorm and IntelliJ IDEA Ultimate.
- The result must satisfy the Milestone 2 verification bar from `specs/PLAN.md`, not just “launches successfully.”

## Expected file areas
- `src/main/kotlin/dev/effect/intellij/devtools/EffectDevToolsService.kt`
- `src/main/kotlin/dev/effect/intellij/devtools/EffectDevToolsModels.kt`
- `src/main/kotlin/dev/effect/intellij/ui/EffectDevToolsToolWindowFactory.kt`
- `src/test/kotlin/dev/effect/intellij/devtools/EffectDevToolsServiceTest.kt`
- `src/test/testData/fixtures/devtools/**`
- `src/main/kotlin/dev/effect/intellij/status/EffectStatusService.kt`
- `src/main/kotlin/dev/effect/intellij/binary/EffectBinaryService.kt`
- `src/main/kotlin/dev/effect/intellij/settings/EffectProjectSettingsService.kt`
- `src/main/kotlin/dev/effect/intellij/lsp/**`
- `src/main/kotlin/dev/effect/intellij/debug/EffectDebugBridgeService.kt`
- `src/main/kotlin/dev/effect/intellij/settings/EffectProjectSettingsConfigurable.kt`
- `src/test/kotlin/dev/effect/intellij/**`
- `specs/handoffs/P5_REVIEW.md`
- `specs/handoffs/P6_TEST.md`

## Steps
1. Align the runtime protocol implementation with the local Effect Dev Tools reference.
   - Read the local encoder and transport code before changing Kotlin parsing logic.
   - Replace synthetic metric assumptions with the real `_tag` / `name` / `tags` shape.
   - Keep tracer handling aligned with the same local reference surface.

2. Fix runtime framing and add protocol-level tests.
   - Correct the outbound `MetricsRequest` and `Pong` payload formatting.
   - Add tests that observe outbound socket traffic, not just inbound parsing.
   - Add at least one realistic metrics fixture derived from the local reference payload shape.
   - Add malformed-payload coverage that proves the error handling still works.

3. Refactor LSP status handling so the widget remains LSP-only.
   - Remove any binary-service `RUNNING` updates that occur before the LSP server is actually initialized.
   - Stop using blanket project settings saves to force LSP restart state unless the changed setting truly affects the LSP process.
   - Add tests for status transitions, including startup, error, restart-required, and non-LSP settings changes.

4. Resolve the debugger instrumentation mismatch honestly.
   - First, determine whether supported JetBrains debug configuration types can be mutated safely in this plugin.
   - If yes, implement the real injection flow and test it.
   - If no, remove or disable the misleading UI and messaging in this pass, and document the deferral explicitly.
   - In either case, the post-fix surface must not imply working instrumentation when none exists.

5. Close the Milestone 2 evidence gap.
   - Add fixture-driven or integration coverage for the core editor behavior where practical.
   - Run manual smoke tests with a real `@effect/tsgo` binary in WebStorm and IntelliJ IDEA Ultimate.
   - Record exact tested behaviors, IDE versions, binary mode used, and outcomes.
   - Treat “server launched” as insufficient evidence on its own.

6. Re-run verification after remediation.
   - `./gradlew test`
   - `./gradlew check build`
   - `timeout 90s ./gradlew runIde`
   - plugin verification if the project is configured to run it in this environment

7. Update docs and handoffs to reflect the new truth.
   - Update `specs/handoffs/P5_REVIEW.md` if a finding is resolved, downgraded, or reclassified.
   - Update `specs/handoffs/P6_TEST.md` with actual verification evidence and any remaining test debt.
   - If any issue remains intentionally deferred, document it explicitly rather than leaving ambiguous behavior in code or UI.

## Deliverables
- source changes that fix the identified issues
- new or updated tests that prove the fixes
- updated fixture data where needed
- updated handoff docs with exact verification results
- no misleading LSP, runtime, or debugger capability claims left in the shipped surface

## Acceptance criteria
- Runtime metrics and tracer handling match the local Effect Dev Tools protocol used by the reference implementation.
- Outbound runtime frames are valid and covered by tests.
- The LSP widget reflects only language-server state and restart needs.
- Debugger instrumentation is either truly implemented or honestly removed/deferred from the user-visible surface.
- Core LSP behavior has real verification evidence on supported IDEs.
- Remaining gaps, if any, are explicitly documented as deferrals rather than bugs.

## Risks and gotchas
- Do not invent protocol fields from the current Kotlin test fixture; prefer the local reference transport.
- Do not “fix” the review by silently broadening Milestone 4 scope without updating docs and tests.
- Do not keep placeholder debugger controls once they are known to be non-functional.
- Be careful not to regress the existing runtime tracer tree while correcting the metrics path.
- Keep WebStorm and IntelliJ IDEA Ultimate support evidence separate; one passing sandbox boot is not proof for both.

## Unknowns to resolve during remediation
- Whether JetBrains exposes a clean enough extension point for the desired debug-configuration instrumentation flow in this plugin.
- How much of the LSP verification can be automated in the current harness versus recorded as manual smoke evidence.
- Whether any additional runtime protocol details beyond metrics need to be tightened once the real reference payloads are exercised end to end.
