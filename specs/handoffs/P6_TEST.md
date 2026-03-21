# P6 Handoff: Test

## Objective
Record the actual remediation verification results, close what could be closed in automation, and leave the remaining parity blockers explicit.

## Required inputs
- implemented plugin
- review findings from P5
- fixture projects and test harness
- `specs/PLAN.md`

## Context carried forward
- Supported IDEs remain WebStorm and IntelliJ IDEA Ultimate.
- The runtime model is direct native `@effect/tsgo` execution.
- Debug and Dev Tools features may use adapted parity; test behavior, not layout mimicry.

## Automated verification completed

- `./gradlew test --tests 'dev.effect.intellij.devtools.EffectDevToolsServiceTest' --tests 'dev.effect.intellij.lsp.EffectLspStatusOwnershipTest'`
  - Passed.
  - Covered the runtime protocol remediation, outbound frame formatting, malformed runtime payload handling, and the LSP widget ownership/status fixes.
- `./gradlew test`
  - Passed.
- `./gradlew check build`
  - Passed.
  - Includes the post-remediation artifact name/id change required to satisfy Marketplace descriptor validation: `dev.effect.jetbrains` / `Effect TSGO`.
- `timeout 90s ./gradlew runIde`
  - Reached WebStorm `2025.3.3` sandbox startup successfully.
  - Timed out intentionally after boot; this is startup proof, not full editor-feature proof.
- `./gradlew verifyPlugin`
  - Passed.
  - Plugin Verifier reported compatibility against `WS-253.32098.39` and `WS-261.22158.185`.
  - Follow-up warnings remain: `1` scheduled-for-removal API usage, `8` deprecated API usages, and `6` experimental API usages.

## Coverage added during remediation

- Runtime Dev Tools coverage now uses a realistic fixture at `src/test/testData/fixtures/devtools/metrics/reference.json`.
- Runtime protocol tests now assert:
  - parsing of the real `_tag` / `name` / `tags` metric payload shape
  - exact outbound `{"_tag":"MetricsRequest"}\n` framing
  - exact outbound `{"_tag":"Pong"}\n` framing
  - malformed payload handling without shutting the runtime server down
- LSP status ownership tests now assert:
  - binary resolution does not mark the widget `RUNNING`
  - server startup and initialization drive the widget lifecycle
  - only LSP-relevant settings trigger `RESTART_REQUIRED`
  - invalid launch configuration surfaces `ERROR`

## Real `@effect/tsgo` probe

- Command used:
  - `BINARY=$(npm exec --yes --package @effect/tsgo -- effect-tsgo get-exe-path) && node scripts/verify-real-tsgo-lsp.mjs --binary "$BINARY"`
- Important binary nuance:
  - `effect-tsgo` from npm is a wrapper CLI with `patch`, `unpatch`, `get-exe-path`, and `setup`.
  - The wrapper itself is not the raw LSP server entrypoint for `--lsp --stdio`.
  - Manual mode and raw verification should use the native executable returned by `effect-tsgo get-exe-path`.
- Observed result:
  - The native executable initialized successfully and advertised the expected capabilities, including hover, completion, document/workspace symbols, inlay hints, and diagnostics.
  - The verifier script timed out on the first `textDocument/hover` request in this environment.
- Interpretation:
  - We now have honest evidence that the native binary launches and negotiates the expected LSP surface.
  - We still do not have end-to-end proof for actual editor behavior such as hover contents, diagnostics rendering, code actions, completion items, or layer-link behavior.

## Manual smoke status

- WebStorm:
  - Sandbox startup is verified through `runIde`.
  - No recorded manual editor smoke pass was completed in this remediation turn.
- IntelliJ IDEA Ultimate:
  - No recorded manual smoke pass was completed in this remediation turn.

## Acceptance status

- Runtime Dev Tools protocol parity issue: fixed and covered.
- Runtime outbound framing bug: fixed and covered.
- LSP widget ownership bug: fixed and covered.
- Misleading debugger instrumentation surface: fixed by removing the non-functional controls and making the remaining guidance explicit.
- Core LSP Milestone 2 verification bar from `specs/PLAN.md`: not yet fully met.

## Milestone 2 verification map

- `all three binary modes work and fail clearly when misconfigured`
  - Partially closed in automation through resolver and status coverage, but still missing recorded live IDE verification for `LATEST`, `PINNED`, and `MANUAL`.
- `supported TypeScript files start the server and show standard @effect/tsgo editor features`
  - Partially closed in automation through native-binary initialize/capability negotiation, but still missing end-to-end proof for diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols, and hover-based layer links.
- `widget state transitions and restart behavior are covered by tests`
  - Closed in automation by `EffectLspStatusOwnershipTest`.
- `manual smoke tests pass in WebStorm and IntelliJ IDEA Ultimate`
  - Still open.

## Remaining gaps

- Manual smoke evidence is still required in both WebStorm and IntelliJ IDEA Ultimate.
- End-to-end proof is still required for diagnostics, code actions, completion, hover, inlay hints, document/workspace symbols, and hover-based layer graph links.
- Live IDE verification of all three binary modes still needs to be recorded, even though resolver/unit coverage exists.
- Plugin Verifier warnings around scheduled-for-removal, deprecated, and experimental APIs remain follow-up work rather than remediation blockers.
