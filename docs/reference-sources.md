# Reference Sources

This repository previously carried local subtree clones under `.repos/` for research and parity work.
Those clones are intentionally not part of the publication-ready source tree.

| Reference | Upstream | Last imported split | How it was used |
| --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/effect-tsgo | `3989d998ad72898669e85e78a9a88fb6ff2394d9` | Native `@effect/tsgo` LSP behavior, binary package layout, diagnostics, code actions, hover, inlay hints, completions, symbols, and the local execute-command canary |
| Effect v4 | https://github.com/Effect-TS/effect | `e16acae8` | Effect API/reference corpus for diagnostics and Dev Tools expectations |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `c49b1c29e8343b282c025d838176758d59ee36af` | Runtime Dev Tools, metrics, tracer, debugger surface, instrumentation, and layer-mermaid behavior references |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release workflow scaffolding |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `767ac539f06db3f27923cd8bfbc0c7c3ba60022d` | Direct native `@effect/tsgo` language-server launch model |

## Canary Notes

The publication-ready plugin should work with published npm `@effect/tsgo` packages for core LSP
features. The local Layer Mermaid action is intentionally capability-gated and remains experimental
until `_effectGetLayerMermaid` support is available in a published `@effect/tsgo` build.

The last local canary work added two JetBrains-relevant server changes:

- LSP `workspace/executeCommand` support for `_effectGetLayerMermaid`
- normal `exit` EOF logging as informational rather than an error

Future canary validation should happen in an external clone of `Effect-TS/effect-tsgo`, then the plugin
should point `MANUAL` binary mode at that externally built executable.
