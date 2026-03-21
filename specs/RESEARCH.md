# RESEARCH: Effect Tsgo JetBrains Plugin

## Executive summary
The repo contains enough local evidence to define the product clearly:

1. `.repos/zed-effect-tsgo` is the best reference for a direct `@effect/tsgo` LSP integration.
2. `.repos/vscode-extension` is the best reference for Effect Dev Tools parity, not for `@effect/tsgo` editor wiring.
3. `.repos/effect-tsgo` shows that JetBrains should launch the packaged binary directly and must not patch IDE-managed binaries.
4. Core layer graph access is already available through standard `@effect/tsgo` hover links; the blocked part is the VS Code-only local preview command built on a non-standard request path.
5. JetBrains LSP support should target a `2025.3.x` baseline for maximal feature coverage. The official docs still exclude IntelliJ IDEA open source builds and Android Studio, but now also note that unified PyCharm without Pro subscription is supported starting in `2025.1`.

## Locked defaults

### Product split
Treat the product as one plugin with two staged workstreams:

- Workstream A: `@effect/tsgo` LSP integration
  - diagnostics
  - quick fixes and refactors
  - completion and hover
  - layer graph links in hover
  - inlay hints
  - document / workspace symbols
  - binary management
- Workstream B: Effect Dev Tools parity
  - clients, metrics, and tracer views
  - attach-debug-session client bridge
  - debug context / span stack / fibers / breakpoints
  - debug instrumentation injection
  - local layer-mermaid preview convenience command

### Target IDEs
- Primary: WebStorm and IntelliJ IDEA Ultimate
- Secondary later target: unified PyCharm, because the public LSP API docs now list it as supported since `2025.1`
- Unsupported: IntelliJ IDEA Community Edition / open source builds and Android Studio

### Platform baseline
- Implementation target: `2025.3.x`
- Earliest acceptable bootstrap baseline: `2025.2.2`

### Binary lifecycle
- Launch `@effect/tsgo` directly with `--lsp --stdio`
- Provide auto-managed download, pinned version, and manual path modes
- Do not patch JetBrains or project TypeScript binaries

### Dev Tools UX defaults
- Default Dev Tools home: a session-aware `Effect Dev Tools` tool window with tabs and toolbar actions
- JCEF is optional, not mandatory on day one
- Any JCEF-backed tracer must have a browser-less fallback when `JBCefApp.isSupported()` is false

## Parity matrix

| Surface | Scope | Source of truth | JetBrains strategy | Parity target | Evidence / notes |
| --- | --- | --- | --- | --- | --- |
| Diagnostics | Core | `@effect/tsgo` | LSP publish/pull diagnostics | Exact | `effect-tsgo` README plus JetBrains LSP diagnostics support |
| Quick fixes / code actions | Core | `@effect/tsgo` | LSP code actions | Exact | `effect-tsgo` patch inventory plus JetBrains code-action support |
| Completion | Core | `@effect/tsgo` | LSP completion | Exact | `effect-tsgo` completion work plus JetBrains completion support |
| Hover | Core | `@effect/tsgo` | LSP hover / quick docs | Exact | Standard hover path |
| Layer graph links in hover | Core | `@effect/tsgo` | Standard hover output | Exact | Hover tests show Mermaid links; depends on `noExternal` / `mermaidProvider` |
| Inlay hints | Core | `@effect/tsgo` | LSP inlay hints | Exact on `2025.2.2+` | JetBrains adds inlay-hint support in `2025.2.2` |
| Document symbols / structure | Core | `@effect/tsgo` | LSP document symbols, structure, breadcrumbs | Exact on `2025.3+` | JetBrains adds structure/breadcrumbs integration in `2025.3` |
| Refactors | Core | `@effect/tsgo` | LSP code actions / workspace edits | Near-exact | Validate per refactor surface |
| Binary installation / version pinning | Core | Zed | Plugin-managed resolver/cache | Exact | Prefer the Zed install-and-launch model |
| Clients server view | Dev Tools | VS Code | Tool window tab | Adapted | WebSocket server plus active-client selection |
| Metrics view | Dev Tools | VS Code | Tool window tab | Adapted | Polls active client and renders metric trees |
| Tracer tree | Dev Tools | VS Code | Tool window tab | Adapted | Streams spans without requiring JCEF |
| Tracer webview | Dev Tools | VS Code | JCEF panel when supported, fallback otherwise | Adapted | Official JCEF docs require `isSupported()` guard |
| Attach debug session as client | Dev Tools | VS Code | Debug-session action plus bridge service | Adapted | Depends on debug-adapter evaluation and instrumentation |
| Debug context | Dev Tools | VS Code | Session-aware tool window tree | Adapted | Reads fiber context from paused process |
| Debug span stack | Dev Tools | VS Code | Session-aware tool window tree | Adapted | Ignore-list filtering, reveal-to-source |
| Debug fibers | Dev Tools | VS Code | Session-aware tool window tree plus actions | Adapted | Interrupt and reveal-current-span behaviors |
| Debug breakpoints / pause on defects | Dev Tools | VS Code | Session-aware tool window plus debugger action | Adapted | Depends on thread stop/continue events |
| Node debug instrumentation injection | Dev Tools | VS Code | Dedicated run/debug settings or later parity | Adapted | VS Code mutates `NODE_OPTIONS`; JetBrains has no 1:1 clone documented |
| Local layer-mermaid preview command | Dev Tools extra | VS Code | Custom LSP request/notification or deferred parity | Blocked for exact parity | VS Code uses `typescript.tsserverRequest` + `_effectGetLayerMermaid`; JetBrains can send custom requests, but not automatically |

## Evidence map
- Core `@effect/tsgo` claims: `specs/research-reports/02-effect-tsgo-integration.md`
- Dev Tools parity claims: `specs/research-reports/01-vscode-parity-surface.md`
- JetBrains product/version/JCEF claims: `specs/research-reports/03-jetbrains-platform-feasibility.md`
- Build/test/release implications: `specs/research-reports/04-build-test-release.md`

## Key conclusions

### 1. The local references are intentionally asymmetric
The user goal combines a language-server plugin and a runtime-debugging/observability plugin. That is workable, but it must be planned as a staged product rather than a single undifferentiated feature list.

### 2. Zed is the correct reference for process lifecycle
The Zed extension already demonstrates the clean shape JetBrains needs:

- resolve package version
- install/download if needed
- locate platform binary
- launch server directly
- pass initialization options and workspace config through

### 3. Layer graph support splits into a core path and an extra path
`@effect/tsgo` already provides Mermaid layer links in standard hover output, so core layer-graph access is not blocked. The blocked item is the extra VS Code local preview command, which currently depends on `typescript.tsserverRequest` and `_effectGetLayerMermaid`.

### 4. JetBrains support is strong enough for the core plugin and clear enough for staged Dev Tools work
The official LSP API now covers the necessary core features. The limiting factor is not core language support; it is how much of the VS Code debug/Dev Tools surface should be reproduced as JetBrains-native UX in v1.

### 5. JCEF is optional, not a gating requirement
The official JCEF docs require a support check and explicitly allow an alternative non-browser path. That means the advanced tracer panel can remain a later milestone as long as the plugin still exposes tracer data without JCEF.

## Risks and blockers

### Blocker 1: local layer-mermaid preview parity
Exact parity with the VS Code convenience command requires a new custom request/notification path or a JetBrains-specific alternate implementation. Core hover-link support is not blocked.

### Blocker 2: debug UX parity
VS Code's debug sidebar model does not map 1:1 onto JetBrains. The plugin must adopt adapted parity, not literal layout parity.

### Blocker 3: unsupported IDE editions
Any plan that assumes Community Edition / open-source IntelliJ support or Android Studio support is invalid.

### Risk 4: binary management complexity
On-demand installation, cache invalidation, proxy/offline handling, and multi-platform testing need to be first-class design concerns.

## Resolved research decisions
- JCEF is not required on day one if tracer data has a browser-less fallback.
- Core layer graph access belongs in Workstream A because standard hover already carries it.
- The extra local preview command belongs in Workstream B because it depends on a non-standard request path.

## Open questions that remain explicit
- Is full Dev Tools parity mandatory for v1, or is v1 allowed to ship core `@effect/tsgo` parity first?
- Should v1 keep debugger-bound views entirely inside the `Effect Dev Tools` tool window, or add XDebugger affordances immediately?
- Should the plugin auto-download npm artifacts itself, or shell out to a user-managed Node/npm installation when necessary?

## Research outputs
- `specs/research-reports/01-vscode-parity-surface.md`
- `specs/research-reports/02-effect-tsgo-integration.md`
- `specs/research-reports/03-jetbrains-platform-feasibility.md`
- `specs/research-reports/04-build-test-release.md`
