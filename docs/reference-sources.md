# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s (not tracked subtrees), so they can be refreshed without touching plugin history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-08-06) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `b415a1e9dfba278cfe5bb632719ef0f2f5c097cc` | Native `@effect/tsgo` LSP behavior, binary package layout and compatibility metadata, diagnostics, code actions, hover Mermaid link + layer-graph URL encoding |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `4c28b0fb5fc7611e0a0583785a71365a09b11e1d` | Effect v4 corpus; the authoritative devtools/tracer/metrics wire schema and fiber/context runtime internals. Active v4 development moved from `Effect-TS/effect-smol` to `Effect-TS/effect` `main` (the full smol history, including the previous pin `5946da38`, is present there); the clone's origin was repointed accordingly on 2026-07-24 |
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
  .repos/effect-v4 4c28b0fb5fc7611e0a0583785a71365a09b11e1d
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension c49b1c29e8343b282c025d838176758d59ee36af
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream b415a1e9dfba278cfe5bb632719ef0f2f5c097cc
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

As of `@effect/tsgo@0.33.0` (npm `latest`, published 2026-08-06) and tsgo HEAD `b415a1e9` (the
not-yet-published `0.34.0` "Version Packages" commit — the `0.33.0` tag sits at `88c068b7`, five
commits behind the pinned tip), the server still does **not** register any `workspace/executeCommand`
for the layer graph (a full-tree grep at the pin finds `ExecuteCommand` only in the
`shim/lsp/lsproto` protocol shim; `_effectGetLayerMermaid` appears nowhere). The Layer Mermaid graph
is delivered only as `mermaid.live` hover links whose fragment is an encoded `pako:` payload
(base64url of a zlib-compressed `{"code": "<mermaid>"}`), gated on `noExternal=false`; the encoding
in `internal/layergraph/mermaidurl.go` is unchanged across the 0.19.0 → 0.33.0 range. The plugin's
"Show Layer Mermaid Graph" action therefore decodes that hover link into a local `.mmd` preview and
keeps the execute-command probe only as a forward-compatible path. Since 0.24.2 the enhanced Effect
quickinfo is also returned when hovering the asterisk of a `yield*` expression, not just the `yield`
keyword.

Since `@effect/tsgo@0.26.0` the platform packages changed layout: the per-binary `lib/tsc.json` /
`lib/tsc-next.json` metadata files are **gone**, replaced by a single `lib/upstream.json` profile
manifest (`schemaVersion: 2`, `profiles[]` entries with `kind: "ts"`, `binName`, and
`ts.npmVersion` / `ts.gitHead`), and the packages additionally ship tsgolint plus an Oxlint
N-API binding (~40 MB extra payload; both are for the Oxlint linter integration and unused by the
LSP). **Since `0.32.0` (commit `52ec481`, "Normalize upstream components") the layout changed
again**: `lib/upstream.json` is now `schemaVersion: 4` with `tags` (dist-tag → version maps per
component: `typescript.latest`/`typescript.next`, `oxlint.latest`, `oxlint-tsgolint.latest`),
`components` (component → version → `{ gitHead, dependencies? }`), and `profiles[]` repurposed to
mean runtime-compatibility profiles (e.g. `vite-plus`) — **not** TypeScript binary profiles anymore.
Executables moved to per-version directories: `artifacts/typescript/<version>/tsc(.exe)` (both
`latest` and `next` builds), `artifacts/oxlint-tsgolint/<version>/tsgolint`, and
`artifacts/oxlint/<version>/*.node`. `lib/tsc` is still shipped as a compatibility copy of the
`latest` build (byte-identical size in the published 0.33.0 linux-x64 tarball), but **`lib/tsc-next`
no longer exists** — the `next` binary is only available under `artifacts/`. The rewritten
`lib/getExePath.js` validates `schemaVersion === 4`, iterates `components.typescript`, resolves
`artifacts/typescript/<version>/tsc`, and matches the workspace TypeScript package's `gitHead`
(the old `profile.ts.version`-vs-`npmVersion` validation bug is moot — the field is no longer
read). Plugin 0.1.3's managed resolution accepted only `schemaVersion: 2` and looked for
`lib/tsc`/`lib/tsc-next`, so **managed LATEST/PINNED mode hard-failed on 0.32.0+ packages** (health
check rejected the install, reinstalled, then threw `DamagedManagedPackageException`) — restoring
that path was the headline fix of this refresh: since 0.1.4 the plugin parses the component
manifest (any schemaVersion with the component shape, so a compatible future revision keeps full
TypeScript matching), selects the `artifacts/typescript/<version>/` executables, and fails closed
with an actionable error when the manifest shape is uninterpretable. Managed
resolution must still select the candidate whose metadata matches the workspace's native TypeScript
package (`typescript >= 7`, `@typescript/native`, an npm alias, or the older
`@typescript/native-preview`); blindly choosing either executable can mix incompatible
TypeScript-Go revisions. At `0.33.0` the `typescript.latest` tag is still `7.0.2` (gitHead
`2bd066d87f5bafd315be9f40889d0a60b9e58e0b`), matching the real-binary verifier's pin, and
`typescript.next` is `7.1.0-dev.20260805.1` (gitHead `12318e599d21f516defea3b20e5d44b9369da723`;
verified against the published linux-x64 tarball on 2026-08-06). Executable bits remain preserved
at mode **0755** (Changesets v3 + `publishConfig.executableFiles` since 0.26.6, now covering the
`artifacts/` binaries too), so the plugin's permission restoration on extraction stays redundant for
0.26.6+ but remains required for older versions and is kept. The base package still ships the
`effect-tsgo` CLI bin and `@effect/tsgo/lib/getExePath`; since the unreleased `0.34.0` the CLI
`setup` command also supports a non-interactive mode (explicit project, integration, diagnostic,
editor, preview, and apply options). `@effect/language-service` is not a separate install
requirement — it is the tsconfig `plugins[].name` identifier that the bundled `@effect/tsgo` build
honors.

The 0.24.3 → 0.27.1 range published seven new diagnostics: `schemaLiteralNonFinite` (0.25.0, error
by default) and `floatingEffectInVitest` (0.25.0, error by default), then `abortControllerInEffect`,
`catchTagToCatchReason` (Effect v4 only, with quick fixes), `catchChainToFirstSuccessOf`,
`preferUnsafeConstructor` (with quick fix), and `promiseInEffectSuccess` (warning by default), all in
0.26.0 (the rest default to suggestion). The 0.27.1 → 0.33.0 range adds one more:
`preferTypedSchemaDecoder` (0.33.0, style suggestion by default, Effect v4 only, with a "Replace
with `<typedName>`" quick fix) — note it is registered in `internal/rules/rules.go` but **missing
from the shipped `schema.json`** at the pin (still 94 severity entries; apparent upstream
regeneration drift). 0.31.0 also added contextual quick fixes without new diagnostics: the
`floatingEffectYield` fixable offers a `yield*` rewrite for floating Effects inside yieldable
generator contexts, and the `catchTagToCatchReason` fixes now preserve narrowed wrapper error
references. 0.33.0 additionally updated Schema class completions for the Effect beta.104
`Error`/`TaggedError` renames and suppresses auto-imports from blocked Effect internal modules.
Between Effect beta.103 and beta.104, `DevToolsSchema.ts` is untouched — the only scoped devtools
change is in `DevToolsClient.ts`, which now sends a snapshot (`{ ...span }`) instead of the live
span object at span start/end, fixing stale in-place-mutated span states in the wire payloads; the
JSON wire format is unchanged, so this refresh again requires protocol regression smoke rather than
a decoder rewrite. The remaining scoped churn is OTLP-exporter-only (`unstable/observability/`),
which the plugin does not consume. The pinned `main` tip sits 6 commits past the
`effect@4.0.0-beta.104` tag, with an empty scoped diff between tag and tip for both directories.
