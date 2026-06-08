# Plan Slice: Upstream TSGO And Effect V4 Refresh

## Objective
Refresh the JetBrains plugin against the current upstream `@effect/tsgo` feature set and the current published Effect v4 beta line without replacing the existing direct-binary launch architecture.

## Source checkout
Temporary subtree workspace created for this comparison:

- `/tmp/effect-jetbrains-upstreams.QawdLE`

The temp repo was initialized with squashed `git subtree add` imports for:

| Prefix | Upstream | Current head | Previous imported split |
| --- | --- | --- | --- |
| `repos/tsgo` | `https://github.com/Effect-TS/tsgo.git` | `b10ecba36a17` | `3989d998ad72` |
| `repos/effect-smol` | `https://github.com/Effect-TS/effect-smol.git` | `09809f60f19e` | `e16acae83553` |
| `repos/effect-zed` | `https://github.com/RATIU5/zed-effect-tsgo.git` | `eb272c95fc2e` | `767ac539f06d` |
| `repos/effect-vscode` | `https://github.com/effect-ts/vscode-extension.git` | `c49b1c29e834` | `c49b1c29e834` |

Notes:

- The earlier `docs/reference-sources.md` names `https://github.com/Effect-TS/effect-tsgo` and `https://github.com/Effect-TS/effect` were stale; the live source set for this pass resolved to `Effect-TS/tsgo` and `Effect-TS/effect-smol`.
- `Effect-TS/effect-tsgo` rejected anonymous fetches as missing/private, while `Effect-TS/tsgo` fetched successfully and contained the previously imported `3989d998ad72` split.
- The VS Code extension has not moved since the previous import.

## Upstream delta
`@effect/tsgo` moved from `0.11.4` to `0.14.1`.

Relevant changes:

- Adds `catchToOrElseSucceed`, a fixable style diagnostic for `Effect.catch(() => Effect.succeed(...))` / `Effect.catchAll(() => Effect.succeed(...))`.
- Adds `redundantOrDie`, a style diagnostic for hoisting repeated generator-level `Effect.orDie`.
- Adds `schemaNumber`, a fixable v4-only diagnostic recommending `Schema.Finite` and `Schema.FiniteFromString`.
- Updates TypeScript-Go from `94f31f32...` to `254e9a53...`.
- Adds a module shim and refreshes generated language-service shims.
- Keeps the existing plugin option surface stable: diagnostics, refactors, quickinfo, completions, goto, renames, Mermaid provider, `noExternal`, `layerGraphFollowDepth`, `inlays`, import-style controls, severity maps, and overrides.

Effect v4 moved from the previous imported `effect@4.0.0-beta.74` snapshot to the published `effect@4.0.0-beta.78` beta tag, with a few unreleased commits on `effect-smol/main`.

Relevant changes:

- Effect npm `latest` is still v3; Effect v4 is on npm dist-tag `beta`.
- Notable beta.75-beta.78 changes include `Schema.Error()` / `Schema.Defect()` constructor APIs, `Schema.isGUID`, max UUID validation updates, `StructWithRest` stricter validation, Schema adapter error alignment, `Workflow.make` RPC-style tag alignment, OTLP environment configuration, OTLP resource precedence changes, and several devtools/tracer/metric/context touched files.
- The plugin does not compile against Effect v4 directly, but its runtime instrumentation and fixture assumptions should be checked against beta.78.

Zed moved from `0.0.4` to `0.0.5`.

Relevant changes:

- Manual binary override is now read from typed `lsp.effect-tsgo.binary.path`, not `lsp.effect-tsgo.settings.binary.path`.
- README now explicitly calls out `wasm32-wasip2` dev-extension requirements.
- The launch command remains the native `tsgo` binary with `--lsp --stdio`.

VS Code remained at the same upstream commit.

Relevant parity surface still worth carrying forward:

- Dev Tools server controls, clients, metrics, tracer, extended tracer webview, debug context, span stack, fibers, breakpoints, pause-on-defects, fiber interrupt, NODE_OPTIONS instrumentation injection, and local layer Mermaid preview through `_effectGetLayerMermaid`.

## Current plugin fit
The JetBrains plugin already matches the important upstream runtime model:

- It resolves or accepts a native platform `tsgo` binary.
- It launches the binary directly as `tsgo --lsp --stdio`.
- It can use `LATEST`, `PINNED`, or `MANUAL` binary modes.
- It passes arbitrary initialization options and workspace configuration JSON through to the LSP server.
- It already capability-gates the local Mermaid action on advertised execute-command support.

That means the new tsgo diagnostics and TypeScript-Go engine changes should mostly arrive through the managed binary update path. The update work should focus on evidence, defaults, docs, and edge-case integration rather than a large LSP rewrite.

## Ordered work
1. Update reference-source documentation.
   - Replace the stale `Effect-TS/effect-tsgo` URL with `Effect-TS/tsgo`.
   - Update the Effect API reference row to `Effect-TS/effect-smol`, which is the live v4 beta source used for this comparison.
   - Record latest heads and the temp subtree comparison command family.

2. Refresh binary-version expectations.
   - Treat `@effect/tsgo@0.14.1` as the latest managed binary target.
   - Keep manual mode as the default, but update docs/examples to prefer native `tsgo` paths and mention the current `effect-tsgo get-exe-path` helper only as discovery.
   - Add a small managed-cache test case that uses version `0.14.1` metadata shape if the current fixtures are too synthetic to catch packaging drift.

3. Add real LSP smoke fixtures for the three new diagnostics.
   - `catchToOrElseSucceed`: assert diagnostics and quick fix/code action behavior.
   - `redundantOrDie`: assert diagnostic rendering and no crash without a fix.
   - `schemaNumber`: assert v4-only diagnostics and quick fixes for `Schema.Number` / `Schema.NumberFromString`.
   - Run the fixture lane against a real `@effect/tsgo@0.14.1` binary, not just mocked service state.

4. Re-check workspace configuration passthrough.
   - Add or refresh examples for `effect.diagnosticSeverity.schemaNumber`, `effect.diagnosticSeverity.redundantOrDie`, and `effect.diagnosticSeverity.catchToOrElseSucceed`.
   - Confirm `effect.inlays`, `effect.mermaidProvider`, `effect.noExternal`, and `effect.layerGraphFollowDepth` still flow through `workspace/configuration`.
   - Consider optional typed UI affordances for the common Effect LSP settings only after the JSON passthrough smoke is green.

5. Re-validate local Mermaid action against current tsgo.
   - First prove whether `@effect/tsgo@0.14.1` advertises either `_effectGetLayerMermaid` or `typescript.tsserverRequest`.
   - If it does not, keep the action experimental and update user-facing docs to say hover Mermaid links are the supported path.
   - If it does, add an end-to-end action test or manual smoke note covering the returned Mermaid source extraction.

6. Re-check Dev Tools instrumentation against `effect@4.0.0-beta.78`.
   - Run the existing metrics, tracer, context, span stack, fibers, breakpoints, pause-on-defects, and fiber-interrupt fixtures against a small beta.78 sample app.
   - Pay special attention to touched upstream areas: `Context`, `Tracer`, `Metric`, `Logger`, `unstable/devtools`, and OTLP/observability.
   - Do not migrate runtime Dev Tools to beta-only APIs unless the VS Code extension or Effect v4 docs require it.

7. Incorporate the Zed manual-binary lesson.
   - Keep JetBrains manual mode as a direct executable path field, which already matches the corrected Zed behavior.
   - Update docs to warn that users should point at the native platform `tsgo` executable, not the package CLI wrapper, when bypassing managed download.

8. Verify release gates.
   - Run `./gradlew check`.
   - Run `./gradlew buildPlugin`.
   - Run `./gradlew verifyPlugin`.
   - Run the real-binary smoke lane with `@effect/tsgo@0.14.1`.
   - Record IDE, OS, binary mode, package version, and outcomes in `docs/usage.md` or a release note before publishing claims.

## Acceptance criteria
- Managed `LATEST` mode resolves `@effect/tsgo@0.14.1` and starts the LSP.
- Manual mode works with a native `tsgo` executable from the `@effect/tsgo` platform package.
- The three new tsgo diagnostics are observed in JetBrains via real LSP diagnostics; fixable diagnostics expose code actions where the server provides them.
- Existing diagnostics, completion, hover, inlay, symbol, and hover Mermaid-link behavior still work.
- Runtime Dev Tools fixtures or manual smoke pass against an `effect@4.0.0-beta.78` sample.
- Reference-source docs no longer point at stale upstreams.

## Risks
- `effect@latest` remains v3, so any sample app that assumes v4 must explicitly install `effect@beta` or `effect@4.0.0-beta.78`.
- `@effect/tsgo` may not advertise the local `_effectGetLayerMermaid` execute-command path in published `0.14.1`; hover Mermaid links should remain the supported baseline.
- JetBrains LSP API coverage may still differ by IDE build for inlay hints, symbols, and command execution, so smoke evidence should name exact IDE versions.
