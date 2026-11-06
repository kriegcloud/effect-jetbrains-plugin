# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s, not subtrees or submodules, so they can be refreshed without touching plugin
history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-11-06) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `bf34a20a49154ceca6ac58b653f36aa4ff405e47` | Native `@effect/tsgo` LSP behavior, platform-package layout and metadata, diagnostics, code actions, and Layer Mermaid transport |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `506eef143bcc1750ce58920aac1e45da7265d103` | Effect v4 corpus and authoritative DevTools, tracer, metrics, fiber, and context runtime shapes |
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
  .repos/effect-v4 506eef143bcc1750ce58920aac1e45da7265d103
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension 64631d41a75770149361703581e923cf6971d5f4
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream bf34a20a49154ceca6ac58b653f36aa4ff405e47
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

### Published `@effect/tsgo` 0.67.4

The refresh target is `@effect/tsgo@0.67.4`; release tag and recorded source pin both resolve to
`bf34a20a49154ceca6ac58b653f36aa4ff405e47`. The published linux-x64 package was inspected directly:

- `lib/upstream.json` remains **schema 5**. Stable TypeScript is `7.0.2`, git head
  `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`; beta is `7.1.0-beta`,
  `3e9236361fa97a9e6c685f4833ac457ae2d8c50a`; next is `7.1.0-dev.20261105.1`,
  `c46bcd6089ea2e5e84e7fcc60845ac884a5b0ac1`.
- Stable ships only as `lib/tsc` (26,869,920 bytes, mode `0755`, SHA-256
  `d7e8362241f74155cd2e972c9e83d5600f8e74a9934cafb9b66046e34775a7e4`), with 108 declaration
  files beside it. Beta and next keep `artifacts/typescript/<version>/tsc` and adjacent standard
  libraries.
- Existing exact-git-head component selection accepts beta and ignores lint/Rust components;
  no resolver production change is required. Regression tests now model the beta component and
  Rust metadata without changing managed selection.

| Component | Version | Dependencies | gitHead |
| --- | --- | --- | --- |
| `typescript` (`latest`) | `7.0.2` | provider `typescript-go` | `2bd066d87f5bafd315be9f40889d0a60b9e58e0b` |
| `typescript` (`beta`) | `7.1.0-beta` | provider `typescript` | `3e9236361fa97a9e6c685f4833ac457ae2d8c50a` |
| `typescript` (`next`) | `7.1.0-dev.20261105.1` | provider `typescript` | `c46bcd6089ea2e5e84e7fcc60845ac884a5b0ac1` |
| `oxlint-tsgolint` | `7.0.2004` | typescript 7.0.2 | `a61289117febcb1a60cf3bc75ef2b6bbccc4b665` |
| `oxlint-tsgolint` | `7.0.2005` | typescript 7.0.2 | `4ebf80fd2b166cba4890c6896220fec4a1462b29` |
| `oxlint-tsgolint` (`latest`) | `7.0.2006` | typescript 7.0.2 | `420f4f134c4802af98780e3dc413f3452d53fe45` |
| `oxlint` | `1.88.0` | not specified | `6941b4ed45ead21c45bc2164cb80a180eed85b43` |
| `oxlint` | `1.89.0` | not specified | `07ed78f4abde4020ca7919381ffea4da8616b78a` |
| `oxlint` | `1.90.0` | not specified | `9b6fff42fa6edf0bec55861b6f0764c7b9acd86b` |
| `oxlint` (`latest`) | `1.91.0` | not specified | `f3fcdcc61b08e9880476279ba21a363967940fa4` |
| `tsrs` (`latest`) | `0.22.0-ts7.1.0-beta` | TypeScript 7.1.0-beta | `625f918c85a3e12824ede39cd127d0f866b8d9c8` |

Seven compatibility profiles remain; Vite+ now spans 1.3.0–1.5.0. Full component git heads and
profile dependencies are recorded in the inspected manifest. Rust and lint executables remain
upstream-managed package contents.

### Rules and options since 0.54.1

The release contains 147 source rules, 18 more than 0.54.1, including now-published
`preferEffectString`; no names were removed or renamed. All are in directive completion.
The default-on suggestions with rewrites are `effectSucceedWithUndefined`, `failCauseToConstructor`,
`flatMapIdentityToFlatten`, `manualMetricTapToTrack`, `mapConstToAs`, `mapSomeCatchNoSuchElement`,
`matchBooleanToIsSuccess`, `matchToResultOrOption`, `resultMatchToFromResult`, and
`suspendFailToFailSync`. The off-by-default additions are `effectTryWithoutCatch`, `globalJson`,
`globalJsonInEffect`, `mapMapErrorToMapBoth`, `provideMergeAllToArray`, `typeUnionToSchemaUnion`,
`yieldInCallArgument`, and `preferEffectString`.

`preferSchemaOverJson` remains functional but deprecated; configuring it reports the
`deprecatedRuleName` configuration warning. Use `globalJson` / `globalJsonInEffect` instead.
New options are `keyPatterns.target: "taggedClass"` and `brandedSchemaClasses`, including per-file
overrides. The latter defaults to all five kinds (`Class`, `TaggedClass`, `Error`, `TaggedError`,
`Opaque`); `[]` disables branding, including completion insertion. These are upstream configuration
behaviors the plugin does not manage.

All five verifier lanes (`healthy`, `failing`, `new-diagnostics`, `diagnostic-directives`, `fix-all`)
pass against `lib/tsc` (7.0.2), `artifacts/typescript/7.1.0-beta/tsc`, and
`artifacts/tsrs/0.22.0-ts7.1.0-beta/tsrs`, with workspace `typescript@7.0.2` and `effect@4.1.2`.
New fixture cases apply `Replace with Effect.as` (`mapConstToAs`) and
`Replace with Effect.flatten` (`flatMapIdentityToFlatten`). Initialize advertises inlay hints,
semantic tokens, and pull diagnostics, with no `executeCommandProvider`. Layer Mermaid remains
encoded-hover transport.

The packaged tsrs executable is 43,123,960 bytes, mode `0755`, SHA-256
`b88418fe9c58ae802fefa9d4f2beb29a79bf38a6e764e58517c45afc8b7350b5`. It reports
`Version 7.1.0-beta+effect-tsgo.0.67.4 (tsrs 0.22.0-ts7.1.0-beta, microsoft/TypeScript@3e9236361fa9)`.
tsrs is the Rust port (`oxc-project/tsrs`) of TypeScript 7.1; it serves the same Effect LSP through
`--lsp --stdio` and can be tried through `MANUAL` mode. It is not a managed selection; interactive
qualification remains follow-up work.

### Effect v4 4.1.2

The LSP and runtime fixtures pin `effect@4.1.2`; its Git release tag and recorded source pin both
resolve to `506eef143bcc1750ce58920aac1e45da7265d103`. The previous source pin was untagged `main`
past the 4.0.5 release, so changes already present there are not new ports in this refresh.

Across the delta, `DevToolsSchema.ts` is unchanged, so the JSON wire shape decoded by the plugin is
unchanged. Completed fibers now clear `cache.span` and `cache.stackFrame` after completion observers
run. Ended spans ignore late mutations and events, and DevTools suppresses duplicate end snapshots.
The plugin captures defect locations during observer notification and consumes span snapshots, so
neither the instrumentation nor the decoder needs a change.

The current-fiber and Context private keys are unchanged. The real-runtime instrumentation lane
passes on `4.0.0-rc.112`, `4.0.0-rc.115`, `4.0.1`, `4.0.5`, and `4.1.2`. Focused 4.1.2 probes
confirm observer-time location visibility, cleared completed-fiber caches, and ignored late span
mutations. These results do not establish paused-session IDE parity.

### Other references

The VS Code and Zed extensions, language service, and IntelliJ template did not move. All four
recorded pins equal their upstream HEAD at the 2026-11-06 check. Only the tsgo and Effect pins moved.
