# Reference Sources

This repository previously carried local subtree clones under `.repos/` for research and parity work.
Those clones are intentionally not part of the publication-ready source tree.

| Reference | Upstream | Last checked/imported revision | How it was used |
| --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `b10ecba36a17eeb0bfe394d349255daaeac2ea85` | Native `@effect/tsgo` LSP behavior, binary package layout, diagnostics, code actions, hover, inlay hints, completions, symbols, and the local execute-command canary |
| Effect v4 | https://github.com/Effect-TS/effect-smol | `09809f60f19ec98232f98b33e33e02ecb7e4fbd6` | Effect v4 API/reference corpus for diagnostics and Dev Tools expectations |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `c49b1c29e8343b282c025d838176758d59ee36af` | Runtime Dev Tools, metrics, tracer, debugger surface, instrumentation, and layer-mermaid behavior references |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release workflow scaffolding |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `eb272c95fc2e53c929695e70133e2775173ecaab` | Direct native `@effect/tsgo` language-server launch model |

For the June 8, 2026 upstream refresh, the comparison workspace was rebuilt in a temporary git repo
with squashed subtrees:

```bash
tmpdir=$(mktemp -d /tmp/effect-jetbrains-upstreams.XXXXXX)
git -C "$tmpdir" init
git -C "$tmpdir" subtree add --prefix repos/tsgo https://github.com/Effect-TS/tsgo.git main --squash
git -C "$tmpdir" subtree add --prefix repos/effect-smol https://github.com/Effect-TS/effect-smol.git main --squash
git -C "$tmpdir" subtree add --prefix repos/effect-zed https://github.com/RATIU5/zed-effect-tsgo.git main --squash
git -C "$tmpdir" subtree add --prefix repos/effect-vscode https://github.com/effect-ts/vscode-extension.git main --squash
```

## Canary Notes

The publication-ready plugin should work with published npm `@effect/tsgo` packages for core LSP
features. The local Layer Mermaid action is intentionally capability-gated and remains experimental
until `_effectGetLayerMermaid` support is available in a published `@effect/tsgo` build.

The last local canary work added two JetBrains-relevant server changes:

- LSP `workspace/executeCommand` support for `_effectGetLayerMermaid`
- normal `exit` EOF logging as informational rather than an error

Future canary validation should happen in an external clone of `Effect-TS/tsgo`, then the plugin
should point `MANUAL` binary mode at that externally built executable.
