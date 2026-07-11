# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s (not tracked subtrees), so they can be refreshed without touching plugin history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-07-10) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `f0d48a67515048d277feb2c184c41cd7cffa51a4` | Native `@effect/tsgo` LSP behavior, binary package layout and compatibility metadata, diagnostics, code actions, hover Mermaid link + layer-graph URL encoding |
| Effect v4 | https://github.com/Effect-TS/effect-smol | `.repos/effect-v4` | `5946da3804a1be5e752b05b96bd058cdba50a1bf` | Effect v4 corpus; the authoritative devtools/tracer/metrics wire schema and fiber/context runtime internals |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29e8343b282c025d838176758d59ee36af` | Runtime Dev Tools, metrics, tracer, debugger surface, and instrumentation references (unchanged since the previous import) |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Direct native `@effect/tsgo` launch model, current `tsc`/legacy `tsgo` executable fallback, and typed `lsp.effect-tsgo.binary.path` setting |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | (not currently cloned) | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

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

checkout_reference https://github.com/Effect-TS/effect-smol.git \
  .repos/effect-v4 5946da3804a1be5e752b05b96bd058cdba50a1bf
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension c49b1c29e8343b282c025d838176758d59ee36af
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream f0d48a67515048d277feb2c184c41cd7cffa51a4
```

The full-history fetch makes the recorded commit available even when it is no longer the depth-1
default-branch tip, and each checkout remains detached so it cannot silently advance. For a future
refresh, inspect and validate the new upstream commits first, then replace the four revision
arguments above and the matching table entries (including the checked date) together. Do not use
`git pull` as a refresh step for these pinned checkouts.

The pre-existing `.repos/effect-tsgo` directory (a locally-built tsgo working tree, including the
native binary that `MANUAL` binary mode may point at) is left untouched by a refresh; the fresh
source clone lives in `.repos/effect-tsgo-upstream`.

## Canary Notes

The publication-ready plugin works with published npm `@effect/tsgo` packages for core LSP features.

As of `@effect/tsgo@0.19.0` and tsgo HEAD `f0d48a67`, the server does **not** register any
`workspace/executeCommand` for the layer graph. The Layer Mermaid graph is delivered only as
`mermaid.live` hover links whose fragment is an encoded `pako:` payload (base64url of a
zlib-compressed `{"code": "<mermaid>"}`), gated on `noExternal=false`. The plugin's "Show Layer
Mermaid Graph" action therefore decodes that hover link into a local `.mmd` preview and keeps the
execute-command probe only as a forward-compatible path.

`@effect/tsgo@0.19.0` platform packages ship `lib/tsc` (built against the stable TypeScript backend)
and `lib/tsc-next` (built against the nightly backend), plus adjacent JSON files containing the TypeScript
version and `gitHead` used to build each binary. Managed resolution must select the candidate whose
metadata matches the workspace's native TypeScript package; blindly choosing either executable can
mix incompatible TypeScript-Go revisions. A native backend can come from `typescript >= 7`,
`@typescript/native`, an npm alias, or the older `@typescript/native-preview` package.
`@effect/language-service` is not a separate install requirement — it is the tsconfig
`plugins[].name` identifier that the bundled `@effect/tsgo` build honors.

The `0.19.0` release also publishes the `flatMapToMap` diagnostic and quick fix; `catchToIgnore` has
been published since `0.16.0`. Between Effect beta.92 and beta.97, the files under
`packages/effect/src/unstable/devtools/` did not change, so this refresh requires protocol regression
smoke rather than a speculative decoder rewrite.
