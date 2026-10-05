# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s, not subtrees or submodules, so they can be refreshed without touching plugin
history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-10-05) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `d1e539c4956bf4b2d1643c58e1477f7597b20d5b` | Native `@effect/tsgo` LSP behavior, platform-package layout and metadata, diagnostics, code actions, and Layer Mermaid transport |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `b1d200c40a1dad69def51ebdbf0a1a612a12b8ac` | Effect v4 corpus and authoritative DevTools, tracer, metrics, fiber, and context runtime shapes |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `64631d41a75770149361703581e923cf6971d5f4` | Runtime DevTools, metrics, tracer, debugger, and injected-instrumentation reference |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Native launch, executable discovery, workspace configuration, and lifecycle reference |
| Effect language service | https://github.com/Effect-TS/language-service | `.repos/effect-language-service` | `690fa515f923a159638a9f78acb51a4bbccd3d36` | Historical diagnostic and schema comparison; the current experience is embedded in `@effect/tsgo` |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `b7b700870b23e1323353a0350a1acd9cc6d2b225` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

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
  .repos/effect-v4 b1d200c40a1dad69def51ebdbf0a1a612a12b8ac
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension 64631d41a75770149361703581e923cf6971d5f4
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream d1e539c4956bf4b2d1643c58e1477f7597b20d5b
checkout_reference https://github.com/Effect-TS/language-service.git \
  .repos/effect-language-service 690fa515f923a159638a9f78acb51a4bbccd3d36
checkout_reference https://github.com/JetBrains/intellij-platform-plugin-template.git \
  .repos/intellij-platform-plugin-template b7b700870b23e1323353a0350a1acd9cc6d2b225
```

The full-history fetch makes recorded commits available even after the default branch advances.
Each checkout stays detached so it cannot silently move. Inspect and validate new upstream commits
before replacing all six revision arguments and table entries together. Do not use `git pull` for
these pinned references.

The pre-existing `.repos/effect-tsgo` directory is not a reference clone and is left untouched. Use
`.repos/effect-tsgo-upstream` when building an unpublished local canary.

## Canary Notes

### Published `@effect/tsgo` 0.48.1

The npm `latest` release at refresh kickoff is `@effect/tsgo@0.48.1`, published 2026-10-05. Its Git
release tag resolves to `d1e539c4956bf4b2d1643c58e1477f7597b20d5b`, which is also the recorded source
pin, so there is no unpublished tip to track in this refresh. The published linux-x64 platform
package was inspected directly:

- `lib/upstream.json` remains **schema 5**, with the same component shape and TypeScript `provider`
  metadata. Stable TypeScript remains `7.0.2` at the same exact git head.
- Executables remain at `artifacts/typescript/<version>/tsc`, with a byte-identical `lib/tsc`
  compatibility copy for the stable build. Both stable files are mode `0755`, size `25,858,208`, and
  SHA-256 `9fdb8079079036c949978bbaeef5d4230fb534d4db2b00dfdad008966428b42a`.
- Since 0.48.0 the compilers are built **without the embedded standard library**. Matching
  `lib.*.d.ts` files ship beside each executable: 107 files next to `lib/tsc` and next to
  `artifacts/typescript/7.0.2/tsc`, and 112 next to the next build. A compiler copied out of the
  package on its own can no longer resolve the standard library.
- The published next component is `7.1.0-dev.20260929.1`; `lib/tsc-next` remains absent. The moving
  TypeScript `next` dist-tag had already advanced to `7.1.0-dev.20261005.1` at inventory time and is
  not the packaged nightly.

| Component | Version | Provider / dependency | `gitHead` |
| --- | --- | --- | --- |
| `typescript` | `7.0.2` | `typescript-go` | `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` |
| `typescript` | `7.1.0-dev.20260929.1` | `typescript` | `0681ef7fa3a2378ccf49645b6d5b7463bdca74bb` |
| `oxlint-tsgolint` | `7.0.2001` | `typescript 7.0.2` | `482dcf70bffce7ea56f63128c74beb67dec658a2` |
| `oxlint-tsgolint` | `7.0.2003` | `typescript 7.0.2` | `eb9339115edde6811ca94c3433adf69ea9852880` |
| `oxlint` | `1.82.0` | not specified | `b4da00b621ec2f6f67ed218f5366c45ed325331b` |
| `oxlint` | `1.83.0` | not specified | `7bf68f70c20f5329251f19be95076f6eab4fe396` |
| `oxlint` | `1.84.0` | not specified | `f02a64a517a69a4eaa4ef83b722a3f10cf633f10` |
| `oxlint` | `1.85.0` | not specified | `288d8cc77984b0a3851c58c423ffe9e6edc79f2e` |
| `oxlint` | `1.86.0` | not specified | `2ae2939bb2fd98796393658b21556b2a2467e047` |

Only TypeScript components declare a provider. The manifest now tags `oxlint-tsgolint.latest`
(`7.0.2003`) alongside `oxlint.latest` (`1.86.0`) and carries seven profiles: the `vite-plus` alias
(Vite+ `1.0.0`, Oxlint `1.85.0`, tsgolint `7.0.2003`) plus versioned `oxlint@<version>` and
`vite-plus@<version>` compatibility profiles. The expanded Oxlint retention that was source-only in
the previous refresh is now published.

The plugin intentionally ignores unknown component metadata, selects only by exact TypeScript
`gitHead`, and parses future schema revisions by proven component shape. Managed installation
extracts the whole platform package and launches the selected compiler in place, so the sibling
library files arrive with it; this package needs updated regression inputs but no production
resolver branch. Uninterpretable manifests still fail closed. Oxlint and tsgolint artifacts remain
upstream-managed package contents; the IDE does not configure or run them independently.

The server still exposes Layer Mermaid graphs through encoded `mermaid.live` hover links. The
published stable compiler does not advertise `executeCommandProvider`, and neither
`_effectGetLayerMermaid` nor a layer-graph `workspace/executeCommand` registration exists at the pin,
so the plugin keeps hover decoding as the supported path and the execute-command probe as a forward
compatibility canary.

### Rules and options since 0.45.0

Five rules are new in the 118-rule release metadata, and all are in directive completion:
`catchIfTagToCatchTag` and `flatMapIgnoredParamToAndThen` (0.46.0, fixable), `catchRefailToTapError`
(0.46.0, v4 only), and `unstableApiUsage` / `experimentalApiUsage` (0.47.0, v4 only, warning by
default). No rule was removed or renamed. `unknownRuleName` (0.46.0) is a configuration diagnostic
for `diagnosticSeverity` keys, not a directive rule, and is absent from the rule metadata.

Configuration additions are upstream behavior the plugin does not manage: the `strict` preset
(0.46.0) and the `allowedUnstableApis` / `allowedExperimentalApis` options (0.48.0), which accept a
module (`effect/http/HttpClient`) or a single export (`effect/http/HttpClient#get`). Release 0.48.1
fixes the reported name for overloaded (dual) exports so per-export entries match.

### Effect v4 4.0.1

The LSP and runtime fixtures pin `effect@4.0.1`, published 2026-10-05; its Git release tag is
`460272d30457f4697d8b8c52cad41caccbcace08`. Effect `4.0.0` was published 2026-10-01 and npm `latest`
now points at the v4 line, so the fixtures use an exact stable version instead of a release
candidate.

Commit `1b4461ec3a` ("Move unstable Effect modules to top-level paths"), included in rc.118 and
later, moves DevTools from `packages/effect/src/unstable/devtools` to `packages/effect/src/devtools`;
the public import becomes `effect/devtools`. Compared across the move, `DevToolsSchema.ts`,
`DevToolsClient.ts`, `DevToolsServer.ts`, and `DevTools.ts` differ only in relative import paths and
documentation comments. The JSON wire shape decoded by the plugin is unchanged. The smoke fixture
imports the new path; the instrumentation verifier rewrites it for releases before rc.118.

The debugger's runtime reads are unchanged since rc.115: `fiber.cache.span` and
`fiber.cache.stackFrame` remain the span and stack-frame sources, and the current-fiber and Context
private keys are unchanged. The real-runtime instrumentation lane passes on `4.0.0-rc.112`,
`4.0.0-rc.115`, and `4.0.1`. No private-key migration, metrics/tracer interception, or DevTools
decoder change is required.

The recorded Effect source pin is three commits past the 4.0.1 tag.

### Other references

The VS Code and Zed extensions did not move. The language-service repository advanced three commits
(Effect rc.118 compatibility, recognizing Effect runners as provide entry points, and a version
bump); its active rules reach the IDE through `@effect/tsgo`, so nothing is ported separately. The
IntelliJ template advanced ten commits of dependency maintenance (Gradle wrapper `9.8.0`, IntelliJ
Platform Gradle Plugin `2.19.0`, Kotlin `2.4.20`, and GitHub Actions bumps). This repository already
carries the Gradle, platform-plugin, Kotlin, `setup-java`, and `free-disk-space` versions through
its own Dependabot updates; `actions/checkout` v7 is left to Dependabot.
