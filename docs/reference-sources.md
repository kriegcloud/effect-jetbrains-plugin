# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s (not tracked subtrees), so they can be refreshed without touching plugin history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-06-30) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `dbc279b1877fabc3e81c4577e977bd3210fa53c2` | Native `@effect/tsgo` LSP behavior, binary package layout, diagnostics, code actions, hover Mermaid link + layer-graph URL encoding |
| Effect v4 | https://github.com/Effect-TS/effect-smol | `.repos/effect-v4` | `e11cccc7d5fe631abccc7d6e3bd296938de0fa2e` | Effect v4 corpus; the authoritative devtools/tracer/metrics wire schema and fiber/context runtime internals |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `c49b1c29e8343b282c025d838176758d59ee36af` | Runtime Dev Tools, metrics, tracer, debugger surface, and instrumentation references (unchanged since the previous import) |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `eb272c95fc2e53c929695e70133e2775173ecaab` | Direct native `@effect/tsgo` launch model and the typed `lsp.effect-tsgo.binary.path` setting |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | (not currently cloned) | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

## Refreshing the local clones

```bash
git clone --depth 1 --single-branch https://github.com/Effect-TS/effect-smol.git      .repos/effect-v4
git clone --depth 1 --single-branch https://github.com/effect-ts/vscode-extension.git .repos/effect-vscode-extension
git clone --depth 1 --single-branch https://github.com/RATIU5/zed-effect-tsgo.git      .repos/effect-zed-tsgo-extension
git clone --depth 1 --single-branch https://github.com/Effect-TS/tsgo.git             .repos/effect-tsgo-upstream
```

The pre-existing `.repos/effect-tsgo` directory (a locally-built tsgo working tree, including the
native binary that `MANUAL` binary mode may point at) is left untouched by a refresh; the fresh
source clone lives in `.repos/effect-tsgo-upstream`.

## Canary Notes

The publication-ready plugin works with published npm `@effect/tsgo` packages for core LSP features.

As of `@effect/tsgo@0.15.0` and tsgo HEAD `dbc279b1`, the server does **not** register any
`workspace/executeCommand` for the layer graph. The Layer Mermaid graph is delivered only as
`mermaid.live` hover links whose fragment is an encoded `pako:` payload (base64url of a
zlib-compressed `{"code": "<mermaid>"}`), gated on `noExternal=false`. The plugin's "Show Layer
Mermaid Graph" action therefore decodes that hover link into a local `.mmd` preview and keeps the
execute-command probe only as a forward-compatible path.

`@effect/tsgo` platform packages ship two binaries since `0.14.6`: `lib/tsgo` (the LSP build, which
the plugin resolves) and `lib/tsc`. A native TypeScript backend is still expected in the workspace,
now satisfied by **either** `@typescript/native-preview` **or** `typescript >= 7`. `@effect/language-service`
is not a separate install requirement — it is the tsconfig `plugins[].name` identifier that the
bundled `@effect/tsgo` build honors.
