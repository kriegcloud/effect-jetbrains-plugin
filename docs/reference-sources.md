# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s (not tracked subtrees), so they can be refreshed without touching plugin history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-08-03) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `431711f645f6e8368efaed5a4fdf0005fc511302` | Native `@effect/tsgo` LSP behavior, binary package layout and compatibility metadata, diagnostics, code actions, hover Mermaid link + layer-graph URL encoding |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `5b3ab384c4b49089da395f5465ba9631ee7cb7c6` | Effect v4 corpus; the authoritative devtools/tracer/metrics wire schema and fiber/context runtime internals. Active v4 development moved from `Effect-TS/effect-smol` to `Effect-TS/effect` `main` (the full smol history, including the previous pin `5946da38`, is present there); the clone's origin was repointed accordingly on 2026-07-24 |
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
  .repos/effect-v4 5b3ab384c4b49089da395f5465ba9631ee7cb7c6
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension c49b1c29e8343b282c025d838176758d59ee36af
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream 431711f645f6e8368efaed5a4fdf0005fc511302
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

The pre-existing `.repos/effect-tsgo` directory is left untouched by a refresh; the fresh source
clone lives in `.repos/effect-tsgo-upstream`. Historically `.repos/effect-tsgo` held a locally-built
tsgo working tree (whose native binary `MANUAL` binary mode may point at), but as of 2026-07-24 it
actually contains a clone of this plugin's own repository — rebuild it from
`.repos/effect-tsgo-upstream` if a local canary binary is needed again, or delete it.

## Canary Notes

The publication-ready plugin works with published npm `@effect/tsgo` packages for core LSP features.

As of `@effect/tsgo@0.27.1` and tsgo HEAD `431711f6`, the server still does **not** register any
`workspace/executeCommand` for the layer graph (a full-tree grep at the pin finds `ExecuteCommand`
only in the `shim/lsp/lsproto` protocol shim; `_effectGetLayerMermaid` appears nowhere). The Layer
Mermaid graph is delivered only as `mermaid.live` hover links whose fragment is an encoded `pako:`
payload (base64url of a zlib-compressed `{"code": "<mermaid>"}`), gated on `noExternal=false`; the
encoding in `internal/layergraph/mermaidurl.go` is unchanged across the 0.19.0 → 0.27.1 range. The
plugin's "Show Layer Mermaid Graph" action therefore decodes that hover link into a local `.mmd`
preview and keeps the execute-command probe only as a forward-compatible path. Since 0.24.2 the
enhanced Effect quickinfo is also returned when hovering the asterisk of a `yield*` expression, not
just the `yield` keyword.

Since `@effect/tsgo@0.26.0` the platform packages changed layout: the per-binary `lib/tsc.json` /
`lib/tsc-next.json` metadata files are **gone**, replaced by a single `lib/upstream.json` profile
manifest (`schemaVersion: 2`, `profiles[]` entries with `kind: "ts"`, `binName`, and
`ts.npmVersion` / `ts.gitHead`), and the packages additionally ship `lib/tsgolint` plus an Oxlint
N-API binding (~40 MB extra payload; both are for the Oxlint linter integration and unused by the
LSP). The plugin's managed resolution reads `upstream.json` for 0.26+ packages and keeps the
per-binary-JSON path for 0.19–0.25 plus the legacy `lib/tsgo` fallback. Managed resolution must
still select the candidate whose metadata matches the workspace's native TypeScript package
(`typescript >= 7`, `@typescript/native`, an npm alias, or the older `@typescript/native-preview`);
blindly choosing either executable can mix incompatible TypeScript-Go revisions. At `0.27.1` the
`latest` profile is still `typescript@7.0.2` (gitHead `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`),
matching the real-binary verifier's pin, and the `next` profile is `7.1.0-dev.20260803.1` (verified
against the published linux-x64 tarball on 2026-08-03). That tarball now carries
`tsc`/`tsc-next`/`tsgolint` at mode **0755** — the Changesets v3 + `publishConfig.executableFiles`
pipeline (0.26.6) finally preserves executable bits — so the plugin's permission restoration on
extraction is redundant for 0.26.6+ but remains required for older versions and is kept. Upstream
also added an `effect-tsgo` CLI bin and a `@effect/tsgo/lib/getExePath` helper to the base package;
note that at the pin `getExePath.js` validates `profile.ts.version` while the shipped manifest field
is `npmVersion` (apparent upstream bug — the plugin parses `npmVersion`). `@effect/language-service`
is not a separate install requirement — it is the tsconfig `plugins[].name` identifier that the
bundled `@effect/tsgo` build honors.

The 0.24.3 → 0.27.1 range publishes seven new diagnostics: `schemaLiteralNonFinite` (0.25.0, error
by default) and `floatingEffectInVitest` (0.25.0, error by default), then `abortControllerInEffect`,
`catchTagToCatchReason` (Effect v4 only, with quick fixes), `catchChainToFirstSuccessOf`,
`preferUnsafeConstructor` (with quick fix), and `promiseInEffectSuccess` (warning by default), all in
0.26.0 (the rest default to suggestion). `preferSchemaTypeProperty` (published since 0.24.0) also
only now appears in the shipped `schema.json`. Between Effect beta.101 and beta.103,
`packages/effect/src/unstable/devtools/DevToolsSchema.ts` only tightened value constraints
(`Schema.Number` → `Schema.Natural` for metric counts and frequency occurrences, Summary quantiles
bounded to [0, 1]); the JSON wire format is unchanged, so this refresh again requires protocol
regression smoke rather than a decoder rewrite. The remaining scoped churn is OTLP-exporter-only
(`unstable/observability/`), which the plugin does not consume, and the `effect@4.0.0-beta.103` tag
matches the pinned `main` tip for both directories (empty scoped diff).
