# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s, not subtrees or submodules, so they can be refreshed without touching plugin
history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-10-20) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `018720b8553d9198c5d69b060175ef663e3baa10` | Native `@effect/tsgo` LSP behavior, platform-package layout and metadata, diagnostics, code actions, and Layer Mermaid transport |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `1b740862e3d25ec5d4b6f86b9fe792b6dabbf84c` | Effect v4 corpus and authoritative DevTools, tracer, metrics, fiber, and context runtime shapes |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `64631d41a75770149361703581e923cf6971d5f4` | Runtime DevTools, metrics, tracer, debugger, and injected-instrumentation reference |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Native launch, executable discovery, workspace configuration, and lifecycle reference |
| Effect language service | https://github.com/Effect-TS/language-service | `.repos/effect-language-service` | `cb91d700a10e86de46d57b94261d4dcc9c92fc20` | Historical diagnostic and schema comparison; the current experience is embedded in `@effect/tsgo` |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `c7c9f997a7e67920efc534202e7ec29684d3b161` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

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
  .repos/effect-v4 1b740862e3d25ec5d4b6f86b9fe792b6dabbf84c
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension 64631d41a75770149361703581e923cf6971d5f4
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream 018720b8553d9198c5d69b060175ef663e3baa10
checkout_reference https://github.com/Effect-TS/language-service.git \
  .repos/effect-language-service cb91d700a10e86de46d57b94261d4dcc9c92fc20
checkout_reference https://github.com/JetBrains/intellij-platform-plugin-template.git \
  .repos/intellij-platform-plugin-template c7c9f997a7e67920efc534202e7ec29684d3b161
```

The full-history fetch makes recorded commits available even after the default branch advances.
Each checkout stays detached so it cannot silently move. Inspect and validate new upstream commits
before replacing all six revision arguments and table entries together. Do not use `git pull` for
these pinned references.

The pre-existing `.repos/effect-tsgo` directory is not a reference clone and is left untouched. Use
`.repos/effect-tsgo-upstream` when building an unpublished local canary.

## Canary Notes

### Published `@effect/tsgo` 0.54.1

The npm `latest` release at refresh kickoff is `@effect/tsgo@0.54.1`, published 2026-10-20. Its Git
release tag resolves to `00f79e041ae9ce7432912ef66842a7cbf1f4fafb`; the recorded source pin is two
commits later (`preferEffectString`, #881, and its version-packages commit), so that one rule is
unpublished and deferred. The published linux-x64 platform package was inspected directly:

- `lib/upstream.json` remains **schema 5**, with the same component shape and TypeScript `provider`
  metadata. Stable TypeScript remains `7.0.2` at the same exact git head
  (`2bd066d87f5bafd315be9f40889d0a60b9e58e0b`).
- **Since 0.52.0 the `latest` compiler ships once per package, as `lib/tsc`** (26,595,488 bytes,
  mode `0755`, SHA-256 `20a50cd37506bf52b5392903987e7490b64ca0169d2046f5ff08ce716bb91c8c`) with
  108 `lib.*.d.ts` files beside it; `artifacts/typescript/7.0.2/` no longer exists. The other
  TypeScript builds keep the `artifacts/typescript/<version>/tsc` layout: `next` is
  `7.1.0-dev.20261019.1` at `5237fd46890956e4bb8177a1a05dcd69e4166560` (27,521,184 bytes, with its
  own standard library). `lib/tsc-next` remains absent.
- The plugin's component resolver already falls back to `lib/tsc` for the `latest`-tagged
  component when its artifact is absent, and the install marker tracks whichever executables are
  present, so managed resolution needs no production change for this layout. Regression tests now
  model it (standard library beside `lib/tsc`, healthy-install reuse without a reinstall loop, and
  `next` selection from `artifacts/`).

| Component | Version | Dependencies | gitHead |
| --- | --- | --- | --- |
| `typescript` (`latest`) | `7.0.2` | provider `typescript-go` | `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` |
| `typescript` (`next`) | `7.1.0-dev.20261019.1` | provider `typescript` | `5237fd46890956e4bb8177a1a05dcd69e4166560` |
| `oxlint-tsgolint` | `7.0.2003` | `typescript 7.0.2` | `eb9339115edde6811ca94c3433adf69ea9852880` |
| `oxlint-tsgolint` (`latest`) | `7.0.2004` | `typescript 7.0.2` | `a61289117febcb1a60cf3bc75ef2b6bbccc4b665` |
| `oxlint` | `1.87.0` | not specified | `2bd08ebe8f36fcf1954a675ffdeb4c6d0129f609` |
| `oxlint` | `1.88.0` | not specified | `6941b4ed45ead21c45bc2164cb80a180eed85b43` |
| `oxlint` (`latest`) | `1.89.0` | not specified | `07ed78f4abde4020ca7919381ffea4da8616b78a` |

Only TypeScript components declare a provider. The manifest carries seven profiles: the `vite-plus`
alias (Vite+ `1.3.0`, Oxlint `1.88.0`, tsgolint `7.0.2004`), versioned `vite-plus@1.1.0`–`1.3.0`,
and `oxlint@1.87.0`–`1.89.0`. Oxlint retention rolled forward from 1.82.0–1.86.0 to 1.87.0–1.89.0.

The plugin intentionally ignores unknown component metadata, selects only by exact TypeScript
`gitHead`, and parses future schema revisions by proven component shape. Uninterpretable manifests
still fail closed. Oxlint and tsgolint artifacts remain upstream-managed package contents; the IDE
does not configure or run them independently.

The server still exposes Layer Mermaid graphs through encoded `mermaid.live` hover links. The
published stable compiler does not advertise `executeCommandProvider`, and neither
`_effectGetLayerMermaid` nor a layer-graph `workspace/executeCommand` registration exists at the pin,
so the plugin keeps hover decoding as the supported path and the execute-command probe as a forward
compatibility canary.

### Rules and options since 0.48.1

Eleven rules are new in the 129-rule release metadata, and all are in directive completion:
`apiStabilityLeak` (0.50.0, v4 only, off by default, `maintainers` preset and group);
`effectSleepZeroToYieldNow` and `flatMapToTap` (0.52.0, fixable style suggestions);
`preferEffectArray` and `preferEffectRecord` (0.52.0, off by default, `effect-native` preset);
`preferSchemaTaggedError` (0.52.0, off by default); `schemaClassMissingBrand` (0.52.0, v4 only,
suggestion by default); `errorPayloadShadowsName` (0.53.0, suggestion by default);
`preferNamespaceImport` and `preferBarrelImport` (0.53.0, off by default, driven by
`namespaceImportPackages` / `barrelImportPackages`); and `schemaClassObjectLiteral` (0.54.0, warning
by default). No rule was removed or renamed. `preferEffectString` exists only on `main` past the
0.54.1 tag and is deferred until published.

Configuration additions are upstream behavior the plugin does not manage: the `maintainers` and
`effect-native` presets, the `"identifier"` `keyPatterns` pattern and package-less keys for
`deterministicKeys` (0.53.0), and `namespaceImportPackages` / `barrelImportPackages` /
`importAliases` / `topLevelNamedReexports` inside per-file `overrides` (0.53.0). The `debug` plugin
option and the debug-only `Flows` outline group were removed in 0.52.0; the plugin never wrote or
read them. The 0.53.3 `relatedInformation` change concerns the `diagnostics` CLI output formats,
not the LSP path the IDE uses.

### Effect v4 4.0.5

The LSP and runtime fixtures pin `effect@4.0.5`, published 2026-10-15; its Git release tag is
`7f524e25cfb49a71ac39b0f44278d08d40dda5de`. The recorded Effect source pin is 233 commits past that
tag (`main` as of 2026-10-20).

Across the delta, `DevToolsSchema.ts` is unchanged, so the JSON wire shape decoded by the plugin is
unchanged. `DevToolsClient.ts` (after the 4.0.5 tag, commits `00261cc12c` and `f260830586`) now
sends a `Ping` immediately on connect, retries a failed or dropped connection every second instead
of failing once, and drains the request queue on shutdown only when a writer is ready. These are
client-side behaviors the plugin's DevTools server already tolerates; they are documented, not
ported. `Tracer.ts` gained `@stability stable` annotations only.

The debugger's runtime reads are unchanged since rc.115: `fiber.cache.span` and
`fiber.cache.stackFrame` remain the span and stack-frame sources, and the current-fiber and Context
private keys are unchanged. The real-runtime instrumentation lane passes on `4.0.0-rc.112`,
`4.0.0-rc.115`, `4.0.1`, and `4.0.5`. No private-key migration, metrics/tracer interception, or
DevTools decoder change is required.

### Other references

The VS Code and Zed extensions did not move. The language-service repository advanced four commits
(reporting missed pipeable calls used as method receivers, a `runEffectInsideEffect` fix for Exit
runners and `Effect.context`, Effect 4.0.5, and version bumps); its active rules reach the IDE
through `@effect/tsgo`, so nothing is ported separately. The IntelliJ template advanced five commits
(Gradle wrapper `9.8.1`, Kotlin `2.4.21`, IntelliJ IDEA `2026.2.3`, a `free-disk-space` v2 input
migration, and a GPG signing test). This repository already carries the Gradle wrapper and
`free-disk-space` versions and takes Kotlin `2.4.21` through Dependabot #55.
