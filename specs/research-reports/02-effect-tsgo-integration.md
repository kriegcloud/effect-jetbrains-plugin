# Research Report: `@effect/tsgo` Integration

## Summary
`@effect/tsgo` is the actual language server target. The local repository shows two distribution models:

- Zed-style direct LSP execution of the packaged binary.
- CLI patching of `@typescript/native-preview` for external workflows.

For JetBrains, direct execution is the correct model.

## Language-server feature surface
The local `README.md`, tests, and patch inventory show that `@effect/tsgo` provides:

| Capability | Evidence |
| --- | --- |
| Diagnostics for Effect v3 and v4 | `README.md` diagnostics tables |
| Quick fixes / code actions | `README.md`, `_patches/011-ls-codeactions.patch` |
| Refactors | `README.md` refactor table, `_patches/017-ls-server-refactor-capability.patch` |
| Completions | `README.md` completion table, `_patches/022-ls-completions.patch` |
| Hover | `README.md`, `_patches/012-ls-hover.patch` |
| Inlay hints | `_patches/015-ls-inlay-hints.patch`, README option `inlays` |
| Document symbol post-processing | `_patches/024-ls-document-symbols.patch` |
| Layer graph links in hover | `internal/effecttest/hover_test.go`, README options `mermaidProvider` and `noExternal` |

The important split is that layer graph access already exists in standard hover output. The VS Code-only gap is the extra local preview command, not the underlying graph generation.

## Packaging model

### Published package layout
`@effect/tsgo` ships with platform-specific optional dependencies:

- `@effect/tsgo-win32-x64`
- `@effect/tsgo-win32-arm64`
- `@effect/tsgo-linux-x64`
- `@effect/tsgo-linux-arm64`
- `@effect/tsgo-linux-arm`
- `@effect/tsgo-darwin-x64`
- `@effect/tsgo-darwin-arm64`

Each platform package contains the native `tsgo` binary.

### CLI behavior
The local CLI source shows:

- a direct packaged-binary resolution path
- a patch/unpatch flow for `@typescript/native-preview`
- verification and backup/restore logic

That patch flow is suitable for external Node/npm workflows, not for a JetBrains plugin that controls its own LSP process.

## Zed integration pattern
The Zed extension is the cleanest reference for editor integration:

- resolves the correct platform package
- optionally pins a package version
- installs from npm if needed
- runs the resolved binary with `--lsp --stdio`
- forwards environment variables
- forwards initialization options
- forwards workspace configuration

## JetBrains implications

### Locked recommendation
The JetBrains plugin should:

- launch `@effect/tsgo` directly as an external process
- use `--lsp --stdio`
- never patch JetBrains-bundled binaries
- support both auto-managed and user-managed binary paths

### Required configuration knobs
The plugin should expose:

- version mode: `latest`, `pinned`, or `manual path`
- pinned package version
- binary path override
- extra environment variables
- initialization options passthrough
- workspace configuration passthrough

### Why direct launch wins
- Matches the JetBrains LSP API model.
- Avoids mutating IDE or project-managed TypeScript installations.
- Mirrors the Zed implementation, which is the closest reference to a standalone editor integration.
- Keeps binary lifecycle under plugin control.

## Layer graph nuance to preserve

### Already available through core `@effect/tsgo`
- Hover can already include Mermaid links through standard LSP hover output.
- `mermaidProvider` controls which external Mermaid service those links target.
- `noExternal` can suppress those links entirely.

### Not automatically portable
The VS Code command `effect.showLayerMermaid` is a separate convenience feature. It currently depends on `typescript.tsserverRequest` with `_effectGetLayerMermaid`, which is not standard LSP behavior. For JetBrains, exact parity with that local preview flow requires one of:

1. a custom LSP request/notification path implemented on both sides, or
2. a later milestone that keeps core hover-link support but defers the local preview command.

## Recommendation
Stage implementation in this order:

1. Core LSP startup and binary management
2. Base `@effect/tsgo` editor features through LSP
3. Extra Dev Tools features
4. Non-standard request parity such as local layer-mermaid preview

## Source checkpoints
- `.repos/effect-tsgo/README.md`
- `.repos/effect-tsgo/_packages/tsgo/package.json`
- `.repos/effect-tsgo/_packages/tsgo/src/cli.ts`
- `.repos/effect-tsgo/_patches/011-ls-codeactions.patch`
- `.repos/effect-tsgo/_patches/012-ls-hover.patch`
- `.repos/effect-tsgo/_patches/015-ls-inlay-hints.patch`
- `.repos/effect-tsgo/_patches/017-ls-server-refactor-capability.patch`
- `.repos/effect-tsgo/_patches/022-ls-completions.patch`
- `.repos/effect-tsgo/_patches/024-ls-document-symbols.patch`
- `.repos/effect-tsgo/internal/effecttest/hover_test.go`
- `.repos/zed-effect-tsgo/src/tsgo.rs`
