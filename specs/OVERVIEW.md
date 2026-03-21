# Overview

## Project goal
Build a JetBrains plugin for `@effect/tsgo` and the adjacent Effect Dev Tools surfaces, using the local references as the source of truth for desired behavior and parity.

## Canonical references
- `.repos/zed-effect-tsgo`
  - direct `@effect/tsgo` editor integration reference
- `.repos/effect-tsgo`
  - server, packaging, and feature reference
- `.repos/vscode-extension`
  - Dev Tools parity reference
- `.repos/intellij-platform-plugin-template`
  - build, packaging, and release scaffold

## Phase flow

### P1: RESEARCH
- Produce topic reports and the synthesized `RESEARCH.md`.
- Lock the product split between core LSP integration and Dev Tools parity.

### P2: DESIGN
- Produce architecture, UX, settings, and folder-topology decisions.
- Ensure the design is JetBrains-native and explicit about parity gaps.

### P3: PLAN
- Produce milestone-based implementation instructions, tests, and acceptance criteria.
- Leave no major technical decisions unresolved.

### P4: IMPLEMENT
- Execute Milestone 1 foundation.
- Execute Milestone 2 core LSP parity.
- Execute Milestone 3 Dev Tools parity.

### P5: REVIEW
- Review code, UX, packaging, parity claims, and docs.
- Identify regressions and incomplete parity honestly.

### P6: TEST
- Run automated and manual validation.
- Confirm supported IDEs, fixtures, binaries, and packaging behavior.

## Current defaults
- Commercial JetBrains IDEs only
- Primary targets: WebStorm and IntelliJ IDEA Ultimate
- Platform baseline: `2025.3.x`
- Runtime model: direct `@effect/tsgo --lsp --stdio`
- No IDE binary patching

## Follow-up work
The `.codex` skill for IntelliJ Platform plugins and the MCP-server catalog for this project are intentionally separate follow-up work. They should be researched and implemented only after the core specs package and the main plugin milestones are stable.
