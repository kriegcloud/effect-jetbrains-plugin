# Reference Sources

This repository uses local clones under `.repos/` for research and upstream-parity work. The clones
are intentionally git-ignored and are **not** part of the publication-ready source tree. They are
plain `git clone`s, not subtrees or submodules, so they can be refreshed without touching plugin
history.

| Reference | Upstream | Local path (git-ignored) | Revision checked (2026-08-27) | How it is used |
| --- | --- | --- | --- | --- |
| Effect tsgo | https://github.com/Effect-TS/tsgo | `.repos/effect-tsgo-upstream` | `1ab43807aa20595e83df4a8c5a73b7e8ae7e2e3d` | Native `@effect/tsgo` LSP behavior, platform-package layout and metadata, diagnostics, code actions, and Layer Mermaid transport |
| Effect v4 | https://github.com/Effect-TS/effect | `.repos/effect-v4` | `e72b12fc305710550bc6dcb978e92de8abff88cd` | Effect v4 corpus and authoritative DevTools, tracer, metrics, fiber, and context runtime shapes |
| Effect VS Code extension | https://github.com/effect-ts/vscode-extension | `.repos/effect-vscode-extension` | `64631d41a75770149361703581e923cf6971d5f4` | Runtime DevTools, metrics, tracer, debugger, and injected-instrumentation reference |
| Zed Effect tsgo extension | https://github.com/RATIU5/zed-effect-tsgo | `.repos/effect-zed-tsgo-extension` | `0c4f302c861359b4f9d23f58ac146101030c6229` | Native launch, executable discovery, workspace configuration, and lifecycle reference |
| Effect language service | https://github.com/Effect-TS/language-service | `.repos/effect-language-service` | `5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4` | Historical diagnostic and schema comparison; the current experience is embedded in `@effect/tsgo` |
| IntelliJ Platform Plugin Template | https://github.com/JetBrains/intellij-platform-plugin-template | `.repos/intellij-platform-plugin-template` | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | Gradle, signing, publishing, verifier, Qodana, and release scaffolding reference |

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
  .repos/effect-v4 e72b12fc305710550bc6dcb978e92de8abff88cd
checkout_reference https://github.com/effect-ts/vscode-extension.git \
  .repos/effect-vscode-extension 64631d41a75770149361703581e923cf6971d5f4
checkout_reference https://github.com/RATIU5/zed-effect-tsgo.git \
  .repos/effect-zed-tsgo-extension 0c4f302c861359b4f9d23f58ac146101030c6229
checkout_reference https://github.com/Effect-TS/tsgo.git \
  .repos/effect-tsgo-upstream 1ab43807aa20595e83df4a8c5a73b7e8ae7e2e3d
checkout_reference https://github.com/Effect-TS/language-service.git \
  .repos/effect-language-service 5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4
checkout_reference https://github.com/JetBrains/intellij-platform-plugin-template.git \
  .repos/intellij-platform-plugin-template 7002f57406739f166d0fcf97d23e699a2c4e17dc
```

The full-history fetch makes recorded commits available even after the default branch advances.
Each checkout stays detached so it cannot silently move. Inspect and validate new upstream commits
before replacing all six revision arguments and table entries together. Do not use `git pull` for
these pinned references.

The pre-existing `.repos/effect-tsgo` directory is not a reference clone and is left untouched. Use
`.repos/effect-tsgo-upstream` when building an unpublished local canary.

## Canary Notes

### Published `@effect/tsgo` 0.37.0

The npm `latest` release at refresh kickoff is `@effect/tsgo@0.37.0`, published 2026-08-25 from
commit `2317cf4087dacdd3ea0c022856c19c70bf69858f`. It keeps stable TypeScript at `7.0.2` with git head
`2bd066d87f5bafd315be9f40889d0a60b9e58e0b`. The published linux-x64 platform package was inspected
directly:

- `lib/upstream.json` advances from schema 4 to **schema 5**. TypeScript components add a `provider`
  field (`typescript-go` for stable and `typescript` for next).
- Executables remain at `artifacts/typescript/<version>/tsc`, with a byte-identical `lib/tsc`
  compatibility copy for the stable build. Both files are mode `0755`, size `30,265,506`, and SHA-256
  `881b1b0c1e5d5dbd2cb20c3760373053dbba3d7ffc70fbbebd53ef18ef000382`.
- The published next component is `7.1.0-dev.20260824.1`; `lib/tsc-next` remains absent.
- The release supports both the legacy `microsoft/typescript-go` compiler source and the migrated
  `microsoft/TypeScript/tsc` source through the provider metadata. It also fixes Nix source setup and
  suppresses `unnecessaryPipeChain` when overload arity makes the rewrite unsafe.

The plugin intentionally ignores unknown component metadata, selects only by exact TypeScript
`gitHead`, and parses future schema revisions by proven component shape. Schema 5 therefore needs
regression coverage but no production resolver branch. Uninterpretable manifests still fail closed.
Oxlint and tsgolint artifacts remain upstream-managed package contents; the IDE does not configure or
run them independently.

The server still exposes Layer Mermaid graphs through encoded `mermaid.live` hover links. Neither
`_effectGetLayerMermaid` nor a layer-graph `workspace/executeCommand` registration exists at the pin,
so the plugin keeps hover decoding as the supported path and the execute-command probe as a forward
compatibility canary.

### Unpublished tsgo tip

The recorded tsgo pin is seven commits past 0.37.0. Four diagnostics are present at the pin but are
**not** in the published 0.37.0 binary: `allOfMapToForEach`, `mapSomeToAsSome`, `catchDieToOrDie`, and
the v4-only `catchConditionalRefailToCatchIf`. The first three are fixable. Keep them as future-release
signals; do not claim real-binary coverage until npm publishes a build that contains them. The other
post-tag changes refactor those implementations and improve overload-equivalence detection.

### Effect v4 rc.112

The verifier pins `effect@4.0.0-rc.112`, published 2026-08-25. Compared with rc.108, no file under
`packages/effect/src/unstable/devtools/` changed. The only rc.108-to-rc.112 observability change is
shared hexadecimal tracer-ID generation in `OtlpTracer.ts`; it does not alter the DevTools JSON wire
shape consumed by this plugin. Across the previous recorded Effect pin to current main, the remaining
scoped observability change standardizes OTLP Config constructor naming. Validation is sufficient;
no debugger, tracer, or metrics decoder port is justified.

The recorded Effect main pin is 31 commits past the rc.112 tag. Effect v4 remains prerelease, so the
verifier uses the exact rc version instead of the moving `rc` dist-tag.

### Unchanged references

The VS Code extension, Zed extension, language-service repository, and IntelliJ template did not move
in this refresh. Their prior parity conclusions therefore remain current: no new debugger injection,
launch lifecycle, legacy diagnostic, Gradle, Qodana, verifier, or publication change needs porting.
