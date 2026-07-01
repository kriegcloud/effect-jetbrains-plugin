# Plan Slice: Upstream TSGO 0.15 And Effect beta.92 Refresh

Continuation of `05-upstream-tsgo-effect-v4-refresh.md` (which recorded the 0.11.4 -> 0.14.1 pass).
This slice refreshes against `@effect/tsgo@0.15.0` and `effect@4.0.0-beta.92` and fixes latent v4
runtime-integration bugs surfaced by an adversarially-verified upstream diff (2026-06-30).

## Verified upstream deltas

Local reference clones (git-ignored) refreshed to: tsgo `dbc279b1`, effect-smol `e11cccc7`,
vscode-extension `c49b1c29` (unchanged), zed `eb272c95`. See `docs/reference-sources.md`.

- **@effect/tsgo** npm `latest = 0.15.0` (only dist-tag). Diagnostics shipped in 0.15.0:
  `catchToOrElseSucceed`, `redundantOrDie` (no fix), `schemaNumber` (fix, v4), and **`newSchemaClass`**
  (fix, v4, **off by default**). `catchToIgnore` is HEAD-only / unreleased. **No `executeCommandProvider`**
  is registered — the Layer graph is delivered only as `mermaid.live` hover links (encoded `pako:`,
  gated on `noExternal=false`). Packages ship `lib/tsgo` (LSP) + `lib/tsc`. Native backend now =
  `@typescript/native-preview` OR `typescript>=7`.
- **effect-smol (v4, beta.92)** devtools wire schema (`unstable/devtools/DevToolsSchema.ts`): metrics are
  `{ id, type, description?, attributes:Record<string,string>, state }` (NOT `_tag/name/tags`);
  Frequency `occurrences` and Span `attributes` are ReadonlyMap -> arrays of `[k,v]` pairs; Summary
  `quantiles` are `[q, value?]` tuples. Span `Ended.exit` is `Exit`; a Failure `cause` encodes as a
  bare array of `{_tag:"Die",defect}` / `{_tag:"Fail",error}` / `{_tag:"Interrupt",fiberId?}` where
  defect = `{name?,message,stack?,cause?}`. Fiber/context/span internals moved (global current fiber
  key `~effect/Fiber/currentFiber`, `fiber.context.mapUnsafe`, `fiber.currentStackFrame`, `fiber.id`
  number, `interruptUnsafe`).
- **JetBrains 262**: the `LspServer*` -> `LspClient*` rename is on the 262 branch HEAD but **NOT** in the
  pinned `262.6653.15` build (impl jar still exposes `LspServerManagerImpl` / `startServersIfNeeded`).
  Renaming now would break compilation, so it is deferred until the plugin bumps its platform build.
- **zed**: single commit; typed `lsp.effect-tsgo.binary.path`; launcher owns `--lsp --stdio`. Already at
  parity in JetBrains manual mode.

## Changes made

1. Managed target -> `@effect/tsgo@0.15.0`, effect beta pin -> `4.0.0-beta.92`
   (`docs/usage.md`, `EffectBinaryServiceTest` constant). Resolver already targets `lib/tsgo`.
2. **Metrics decoder fix**: `EffectDevToolsService.parseMetric` now reads `type`/`id`/`attributes` and
   ReadonlyMap-style Frequency occurrences; `metrics/reference.json` fixture corrected to the v4 shape.
3. **Span-exit surfacing**: `parseSpanStatus` decodes `Ended.exit` Failure causes (flat reasons array)
   into a readable outcome; added `tracer/failing-span.json` fixture + test.
4. **Instrumentation rewrite**: `instrumentation.global.js` migrated to v4 fiber/context/span/cause
   internals with v3 fallbacks; `EffectDebugBridgeService` snapshot expression resolves the current
   fiber via the instrumentation (v4 key first).
5. **Layer Mermaid**: `EffectLayerMermaidService` now decodes the `pako:` hover link into a local
   `.mmd` preview (keeps the execute-command probe as a forward-compatible path). Added a decoder
   unit test using a real tsgo-format `pako:` string.
6. Docs: `reference-sources.md` rewritten (local clones, current revisions, Mermaid + backend notes).

## Follow-ups
- Add a `newSchemaClass` smoke fixture (needs the rule enabled since it is off by default).
- Confirm `effect.*` LSP options are honored by the binary vs. only tsconfig `plugins[]`.
- Real-binary smoke against `@effect/tsgo@0.15.0`; Plugin Verifier warning triage on `262.6653.15`.
- Live `effect@4.0.0-beta.92` Dev Tools / debugger smoke (best-effort; validates the runtime fixes).
