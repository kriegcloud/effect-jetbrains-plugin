# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s, not subtrees or submodules, so they can be refreshed without touching plugin
history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-09-16) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `a7414389ab0d325b92684a2b8f50d6f0e02d6c41` | Native `@effect/tsgo` LSP behavior, platform-package layout and metadata, diagnostics, code actions, and Layer Mermaid transport |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `ccae35423188f58d7c3dec5db3e36ed4bf42bcdf` | Effect v4 corpus and authoritative DevTools, tracer, metrics, fiber, and context runtime shapes |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `64631d41a75770149361703581e923cf6971d5f4` | Runtime DevTools, metrics, tracer, debugger, and injected-instrumentation reference |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Native launch, executable discovery, workspace configuration, and lifecycle reference |
| Effect language service | https://github.com/Effect-TS/language-service | `.repos/effect-language-service` | `5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4` | Historical diagnostic and schema comparison; the current experience is embedded in `@effect/tsgo` |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

Effect v4 development previously lived in `Effect-TS/effect-smol`; the full history and current v4
work now live in `Effect-TS/effect`. The VS Code extension's v4 support uses private `~effect/*`
runtime keys while emitting the established domain shapes, so it is a parity signal rather than an
independent wire-schema authority.

## Refreshing the local clones

```bash
set -eu
mkdir -p .repos

checkout_reference() {
  remote="$1"
  directory="$2"
  revision="$3"

  if [ ! -d "$directory/.git" ]; then
    git clone --filter=blob:none --no-checkout "$remote" "$directory"
  fi

  git -C "$directory" remote set-url origin "$remote"
  git -C "$directory" config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
  if [ "$(git -C "$directory" rev-parse --is-shallow-repository)" = "true" ]; then
    git -C "$directory" fetch --unshallow --prune --tags origin
  else
    git -C "$directory" fetch --prune --tags origin
  fi
  git -C "$directory" checkout --detach "$revision"
  test "$(git -C "$directory" rev-parse HEAD)" = "$revision"
}

checkout_reference https://github.com/Effect-TS/effect.git \
  .repos/effect-v4 ccae35423188f58d7c3dec5db3e36ed4bf42bcdf
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension 64631d41a75770149361703581e923cf6971d5f4
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream a7414389ab0d325b92684a2b8f50d6f0e02d6c41
checkout_reference https://github.com/Effect-TS/language-service.git \
  .repos/effect-language-service 5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4
checkout_reference https://github.com/JetBrains/intellij-platform-plugin-template.git \
  .repos/intellij-platform-plugin-template 7002f57406739f166d0fcf97d23e699a2c4e17dc
```

The full-history fetch makes recorded commits available even after the default branch advances.
Each checkout stays detached so it cannot silently move. Inspect and validate new upstream commits
before replacing all six revision arguments and table entries together. Do not use `git pull` for
these pinned references.

The pre-existing `.repos/effect-tsgo` directory is not a reference clone and is left untouched. Use
`.repos/effect-tsgo-upstream` when building an unpublished local canary.

## Canary Notes

### Published `@effect/tsgo` 0.45.0

The npm `latest` release at refresh kickoff is `@effect/tsgo@0.45.0`, published 2026-09-10. Its Git
release tag resolves to `54bbc1e7f0ffe7bb555642312168a88741667c6f`; npm does not expose a `gitHead`
for this release. The published linux-x64 platform package was inspected directly:

- `lib/upstream.json` remains **schema 5**, with the same component shape and TypeScript `provider`
  metadata. Stable TypeScript remains `7.0.2` at the same exact git head.
- Executables remain at `artifacts/typescript/<version>/tsc`, with a byte-identical `lib/tsc`
  compatibility copy for the stable build. Both stable files are mode `0755`, size `30,523,554`, and
  SHA-256 `38c289dac0b72e52c8f8a877e4bee7be8b37ddcc8c02f20c141b570c7be0fe08`.
- The published next component is `7.1.0-dev.20260909.1`; `lib/tsc-next` remains absent. The moving
  TypeScript `next` dist-tag had already advanced to `7.1.0-dev.20260915.1` at inventory time and is
  not the packaged nightly.

| Component | Version | Provider / dependency | `gitHead` |
| --- | --- | --- | --- |
| `typescript` | `7.0.2` | `typescript-go` | `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` |
| `typescript` | `7.1.0-dev.20260909.1` | `typescript` | `f3b04fe05642d53b4ff126a4af05fe2587b43748` |
| `oxlint-tsgolint` | `7.0.2001` | `typescript 7.0.2` | `482dcf70bffce7ea56f63128c74beb67dec658a2` |
| `oxlint` | `1.81.0` | not specified | `0b4e2e67f4193e7ebfcc64982275eb583ae82c83` |
| `oxlint` | `1.82.0` | not specified | `b4da00b621ec2f6f67ed218f5366c45ed325331b` |

Only TypeScript components declare a provider. The package includes executable
`artifacts/oxlint-tsgolint/7.0.2001/tsgolint` (mode `0755`) and
`artifacts/oxlint/{1.81.0,1.82.0}/oxlint.linux-x64-gnu.node` native addons (mode `0644`). The latter
are loadable libraries, not standalone executables. The `vite-plus` profile is the Vite+ `0.3.1`
runtime, selecting Oxlint `1.81.0` and tsgolint `7.0.2001`; `oxlint.latest` selects `1.82.0`.

The plugin intentionally ignores unknown component metadata, selects only by exact TypeScript
`gitHead`, and parses future schema revisions by proven component shape. This package needs updated
regression inputs but no production resolver branch. Uninterpretable manifests still fail closed.
Oxlint and tsgolint artifacts remain upstream-managed package contents; the IDE does not configure or
run them independently.

The server still exposes Layer Mermaid graphs through encoded `mermaid.live` hover links. Neither
published compiler advertises `executeCommandProvider`, and neither `_effectGetLayerMermaid` nor a
layer-graph `workspace/executeCommand` registration exists at the pin, so the plugin keeps hover
decoding as the supported path and the execute-command probe as a forward compatibility canary.

### Unpublished tsgo tip

The recorded tsgo pin is seven commits past the 0.45.0 tag. Three diagnostics are present at the pin
but are **not** in the published binary: `catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and
the v4-only `catchRefailToTapError`. The first two are fixable. They remain excluded from directive
completion and published-binary coverage until a release contains them. The four previously deferred
rules (`allOfMapToForEach`, `mapSomeToAsSome`, `catchDieToOrDie`, and
`catchConditionalRefailToCatchIf`) are now published and included in completion.

Expanded Oxlint retention is also source-only: commit `437b17ea10ab40f1f3baa4ee5f1b75fe300180f6`
retains `1.79.0`, `1.81.0`, `1.82.0`, and `1.83.0` with versioned compatibility profiles and advances
the `vite-plus` alias to `0.3.2`. Those extra artifacts and profiles are not in the inspected release.

### Effect v4 rc.115

The LSP and runtime fixtures pin `effect@4.0.0-rc.115`, published 2026-09-11; its Git release tag is
`4a05d4914fa2327a42bd75fe77c22c188becf3b4`. The DevTools wire schema is unchanged between Effect v4
release candidates 112 and 115: `DevToolsSchema.ts`, `DevTools.ts`, and `DevToolsServer.ts` have no
delta. `DevToolsClient` now takes explicit span snapshots and copies the attributes Map, supporting
the runtime's lazy span properties without changing the JSON shape decoded by the plugin.

Commit `9956f0e6f47096a6e31305ec2c7320b8e1a140af`, included in rc.113 and later, removes
`fiber.currentSpan` and `fiber.currentStackFrame` in favor of `fiber.cache.span` and
`fiber.cache.stackFrame`. Plugin 0.1.7 reads the cache first and falls back to the older fields for
span stacks, source locations, and pause-on-defect reveal. The real-runtime instrumentation lane
checks both sides of that transition; human paused-session evidence remains separate.

The current-fiber and Context private keys are unchanged. The metric registry keys change from
`~effect/Metric/MetricRegistryKey` to `effect/Metric/MetricRegistry` and from
`effect/observability/Metric/FiberRuntimeMetricsKey` to `effect/Metric/FiberRuntimeMetrics`; the
plugin does not use those keys. No private-key migration, metrics/tracer interception, or DevTools
decoder change is required. OTLP exporter and optional stack-capture changes remain runtime concerns.

The recorded Effect source pin is 31 commits past the rc.115 tag. Effect v4 remains prerelease, so
the fixtures use the exact rc version instead of the moving `rc` dist-tag.

### Unchanged references

The VS Code extension, Zed extension, language-service repository, and IntelliJ template did not move
in this refresh. Their prior parity conclusions therefore remain current: no new debugger injection,
launch lifecycle, legacy diagnostic, Gradle, Qodana, verifier, or publication change needs porting.
