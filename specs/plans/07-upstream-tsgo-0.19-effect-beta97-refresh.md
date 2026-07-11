# Evidence Slice: Upstream TSGO 0.19 And Effect beta.97 Refresh

Continuation of `06-upstream-tsgo-0.15-effect-beta92-refresh.md`. This slice records the July 10,
2026 reference refresh and the compatibility work it requires.

## Verified upstream deltas

The git-ignored reference clones were fast-forwarded to tsgo `f0d48a67`, effect-smol `5946da38`,
vscode-extension `c49b1c29` (unchanged), and zed `0c4f302c`.

- `@effect/tsgo@0.19.0` publishes `flatMapToMap` with a `Replace with Effect.map` quick fix;
  `catchToIgnore` has been published since 0.16.0.
- Platform packages now contain `tsc` (built against the stable TypeScript backend) and `tsc-next`
  (built against the nightly backend) with adjacent `{ tsVersion, tsGitHead }` metadata. Binary
  selection must match the workspace TypeScript package's `gitHead`; 0.18 also added native-package
  alias support.
- The 0.16.x line fixed checker crashes involving `import.defer` and bindingless imports, corrected
  layer ordering, and hardened release/version synchronization.
- Effect moved from beta.92 to beta.97. The authoritative
  `packages/effect/src/unstable/devtools/` subtree, including `DevToolsSchema.ts`, is byte-for-byte
  unchanged, so no decoder rewrite is justified by this update.
- Zed 0.0.6 prefers the current `tsc` executable while retaining legacy `tsgo` fallback, documents
  `typescript@7`, and clarifies that the dedicated binary already embeds the language service.

## Plugin changes and evidence

- Directive completion includes the now-published `catchToIgnore` and `flatMapToMap` rules and drops
  stale `setInterval` / `setTimeout` entries (the current rule names are `globalTimers` and
  `globalTimersInEffect`).
- The new-diagnostics fixture proves the `flatMapToMap` diagnostic and `Effect.map` code action.
- Real-binary fixtures install the validated `typescript@7.0.2` package (`gitHead`
  `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`) and the explicit `effect@4.0.0-beta.97` version; they
  no longer install a separate `@effect/language-service` package.
- Managed binary resolution extracts complete platform packages and chooses a metadata-compatible
  current executable while retaining legacy package support.

## Deferred evidence

- Effect beta.97 still needs live runtime Dev Tools/debugger smoke even though the wire schema did not
  change.
- The Layer Mermaid execute-command bridge remains forward-looking; 0.19.0 continues to expose the
  hover-link path instead.
