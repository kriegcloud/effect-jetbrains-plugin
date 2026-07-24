# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s (not tracked subtrees), so they can be refreshed without touching plugin history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-07-24) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `f75c9b929473608376cf286e630f3ec1c86a2ab6` | Native `@effect/tsgo` LSP behavior, binary package layout and compatibility metadata, diagnostics, code actions, hover Mermaid link + layer-graph URL encoding |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `cea1d9c92601e69ebda040af8a1d860d604d885c` | Effect v4 corpus; the authoritative devtools/tracer/metrics wire schema and fiber/context runtime internals. Active v4 development moved from `Effect-TS/effect-smol` to `Effect-TS/effect` `main` (the full smol history, including the previous pin `5946da38`, is present there); the clone's origin was repointed accordingly on 2026-07-24 |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29e8343b282c025d838176758d59ee36af` | Runtime Dev Tools, metrics, tracer, debugger surface, and instrumentation references (unchanged since the previous import) |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Direct native `@effect/tsgo` launch model, current `tsc`/legacy `tsgo` executable fallback, and typed `lsp.effect-tsgo.binary.path` setting |
| Effect language service | https://github.com/Effect-TS/language-service | `.repos/effect-language-service` | `f26d5835d6e3d943368c141417374db00f246e9e` | Optional historical comparison of the original TypeScript-server plugin implementation only; `@effect/tsgo` already embeds the Effect LSP experience, and `@effect/language-service` remains just the tsconfig `plugins[].name` identifier described under Canary Notes |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

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

# The effect-v4 clone previously pointed at Effect-TS/effect-smol.git; `remote set-url` plus the
# full `fetch --prune --tags` migrates it in place (stale effect-smol tags may linger; harmless).
checkout_reference https://github.com/Effect-TS/effect.git \
  .repos/effect-v4 cea1d9c92601e69ebda040af8a1d860d604d885c
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension c49b1c29e8343b282c025d838176758d59ee36af
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream f75c9b929473608376cf286e630f3ec1c86a2ab6
checkout_reference https://github.com/Effect-TS/language-service.git \
  .repos/effect-language-service f26d5835d6e3d943368c141417374db00f246e9e
checkout_reference https://github.com/JetBrains/intellij-platform-plugin-template.git \
  .repos/intellij-platform-plugin-template 7002f57406739f166d0fcf97d23e699a2c4e17dc
```

The full-history fetch makes the recorded commit available even when it is no longer the depth-1
default-branch tip, and each checkout remains detached so it cannot silently advance. For a future
refresh, inspect and validate the new upstream commits first, then replace the six revision
arguments above and the matching table entries (including the checked date) together. Do not use
`git pull` as a refresh step for these pinned checkouts.

The pre-existing `.repos/effect-tsgo` directory (a locally-built tsgo working tree, including the
native binary that `MANUAL` binary mode may point at) is left untouched by a refresh; the fresh
source clone lives in `.repos/effect-tsgo-upstream`.

## Canary Notes

The publication-ready plugin works with published npm `@effect/tsgo` packages for core LSP features.

As of `@effect/tsgo@0.24.3` and tsgo HEAD `f75c9b92`, the server still does **not** register any
`workspace/executeCommand` for the layer graph (a full-tree grep at the pin finds registrations only
for quickinfo, document symbols, inlay hints, and codefix hooks; `_effectGetLayerMermaid` appears
nowhere). The Layer Mermaid graph is delivered only as `mermaid.live` hover links whose fragment is
an encoded `pako:` payload (base64url of a zlib-compressed `{"code": "<mermaid>"}`), gated on
`noExternal=false`; the encoding in `internal/layergraph/mermaidurl.go` is unchanged across the
0.19.0 → 0.24.3 range. The plugin's "Show Layer Mermaid Graph" action therefore decodes that hover
link into a local `.mmd` preview and keeps the execute-command probe only as a forward-compatible
path. Since 0.24.2 the enhanced Effect quickinfo is also returned when hovering the asterisk of a
`yield*` expression, not just the `yield` keyword.

`@effect/tsgo@0.24.3` platform packages ship the same layout as 0.19.0: `lib/tsc` (built against the
stable TypeScript backend) and `lib/tsc-next` (built against the nightly backend), plus adjacent JSON
files containing the TypeScript version and `gitHead` used to build each binary; the platform-package
set and metadata field names are unchanged across the range. Managed resolution must select the
candidate whose metadata matches the workspace's native TypeScript package; blindly choosing either
executable can mix incompatible TypeScript-Go revisions. A native backend can come from
`typescript >= 7`, `@typescript/native`, an npm alias, or the older `@typescript/native-preview`
package. At `0.24.3`, `lib/tsc` is still built from `typescript@7.0.2` (gitHead `2bd066d8`), matching
the real-binary verifier's pin. Since `0.24.3` the release pipeline preserves executable permissions
(0755) on Unix binaries inside the npm tarballs; the plugin's own permission restoration remains as a
defensive measure for older releases. The base `@effect/tsgo` package now also ships a
`schema.json` describing the language-service options. `@effect/language-service` is not a separate
install requirement — it is the tsconfig `plugins[].name` identifier that the bundled `@effect/tsgo`
build honors.

The 0.19.0 → 0.24.3 range publishes four new diagnostics: `missingPipeableSignature` (since 0.21.0,
off by default, no quick fix), `schemaOpaqueInstanceMember` (since 0.22.0, error by default, Effect
v4 only, no quick fix), `syncToSucceed` (since 0.23.0, suggestion by default, with quick fix), and
`preferSchemaTypeProperty` (since 0.24.0, off by default, with quick fix). `flatMapToMap` has been
published since `0.19.0` and `catchToIgnore` since `0.16.0`. Between Effect beta.97 and beta.101,
the files under `packages/effect/src/unstable/devtools/` and `unstable/observability/` did not change
(verified by an empty scoped diff between the pins), so this refresh again requires protocol
regression smoke rather than a decoder rewrite; note that Effect v4 development moved from
`Effect-TS/effect-smol` to `Effect-TS/effect` `main` with beta.99 the first release cut from the
canonical repository.
