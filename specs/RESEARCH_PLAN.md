# Research Plan: Effect Tsgo JetBrains Plugin

## Objective
Produce a decision-complete specification set for a JetBrains plugin that:

1. Integrates `@effect/tsgo` as the TypeScript language server inside JetBrains IDEs.
2. Reaches maximum feasible parity with the local reference implementations.
3. Separates true `@effect/tsgo` scope from the extra Effect Dev Tools scope found in `.repos/vscode-extension`.

## Ground Truth

### Local references
- `.repos/zed-effect-tsgo`
  - Direct `@effect/tsgo` LSP integration for Zed.
  - Best reference for binary acquisition, version pinning, and passing settings into the server.
- `.repos/effect-tsgo`
  - Source of the server, CLI packaging model, feature surface, and platform binaries.
- `.repos/vscode-extension`
  - Effect Dev Tools plugin, not a pure `@effect/tsgo` editor integration.
  - Useful for observability, debug, metrics, tracer, and instrumentation parity targets.
- `.repos/intellij-platform-plugin-template`
  - Starting scaffold for Gradle, plugin metadata, tests, signing, verification, and packaging.

### Critical scope clarification
The request combines two adjacent but different scopes:

- `@effect/tsgo` editor/LSP parity: diagnostics, quick fixes, completions, hover, inlay hints, document symbols, and related language features.
- Effect Dev Tools parity from VS Code: clients, tracer, metrics, debug context/span stack/fibers/breakpoints, tracer webview, and debug instrumentation.

The research must preserve that split so later implementation can stage the work without losing the overall parity goal.

## Research Streams

### 1. Reference inventory
Identify every user-facing surface, configuration point, transport, and lifecycle behavior in:

- `.repos/zed-effect-tsgo`
- `.repos/effect-tsgo`
- `.repos/vscode-extension`
- `.repos/intellij-platform-plugin-template`

Output:
- `specs/research-reports/01-vscode-parity-surface.md`
- `specs/research-reports/02-effect-tsgo-integration.md`
- `specs/research-reports/03-jetbrains-platform-feasibility.md`
- `specs/research-reports/04-build-test-release.md`

### 2. Parity and feasibility analysis
For each feature or workflow, determine:

- Whether it belongs to core `@effect/tsgo` scope or Dev Tools scope.
- Whether JetBrains has a direct equivalent.
- Whether parity should be exact, adapted, or explicitly non-parity with fallback.
- Whether support depends on a JetBrains platform version, IDE edition, or bundled plugin.

### 3. Distribution and runtime strategy
Research and lock decisions for:

- Binary acquisition: bundled, downloaded, or user-specified path.
- Version control: latest vs pinned vs explicit path.
- LSP transport: `--lsp --stdio` direct server execution.
- Whether IDE binary patching is allowed.

Default expectation:
- No IDE binary patching.
- The plugin owns binary resolution and launches `@effect/tsgo` directly.

### 4. JetBrains UX and architecture mapping
Map reference behavior onto JetBrains primitives:

- LSP provider and descriptor
- Tool windows
- Settings/configurable UI
- Status bar widget
- Output/logging
- Debugger integration
- Embedded tracer UI

### 5. Testing and release readiness
Research how the eventual implementation will be validated:

- Integration tests for LSP startup and language features
- Fixture projects for Effect v4 diagnostics and quick fixes
- IDE matrix and OS matrix
- Plugin verifier, signing, and Marketplace packaging

## Deliverables

### Required research artifacts
- `specs/research-reports/*.md`
- `specs/RESEARCH.md`

### Required contents of the synthesis
- A parity matrix
- A recommended target IDE/platform baseline
- Known blockers and non-obvious gaps
- Locked defaults for binary management and plugin scope
- Open questions that must be answered before implementation

## Review Checklist
Before considering research complete, verify that the research package:

- Distinguishes `@effect/tsgo` features from Effect Dev Tools features.
- Names the concrete local sources behind each major conclusion.
- States where JetBrains parity is exact, adapted, or impossible.
- Avoids vague wording like "probably", "maybe", or "should be possible" without a fallback.
- Leaves no ambiguity about target IDEs, baseline JetBrains version, or binary strategy.
