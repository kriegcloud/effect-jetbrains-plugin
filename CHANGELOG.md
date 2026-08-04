# Changelog

## [0.1.3]

- Updated managed binary support and compatibility coverage to `@effect/tsgo` 0.27.1 with `effect` 4.0.0-beta.103. Since `@effect/tsgo` 0.26.0 the platform packages replace the per-binary `tsc.json`/`tsc-next.json` metadata with a single `lib/upstream.json` profile manifest (`schemaVersion` 2) and additionally ship Oxlint/tsgolint artifacts; managed resolution now reads the manifest, keeps the per-binary metadata path for 0.19–0.25 packages plus the legacy `lib/tsgo` fallback, and tolerates the extra files. The stable pairing is unchanged: the `latest` profile still builds `lib/tsc` from `typescript@7.0.2`.
- npm tarballs preserve Unix executable bits since `@effect/tsgo` 0.26.6 (verified live for linux-x64 at 0.27.1), so the plugin's permission restoration on extraction now only matters for older versions; it is retained for them.
- Added `abortControllerInEffect`, `catchChainToFirstSuccessOf`, `catchTagToCatchReason`, `floatingEffectInVitest`, `preferUnsafeConstructor`, `promiseInEffectSuccess`, and `schemaLiteralNonFinite` to Effect diagnostic directive completion, with real-binary coverage for `preferUnsafeConstructor` (including its `Replace with Scope.makeUnsafe` quick fix) and `promiseInEffectSuccess`.
- Note: `floatingEffectInVitest` and `schemaLiteralNonFinite` report at `error` severity by default and `promiseInEffectSuccess` at `warning`; the remaining new diagnostics default to `suggestion`.
- Updated the upstream reference pins and canary notes for tsgo 0.27.1 and Effect 4.0.0-beta.103; the Dev Tools wire schema only tightened value constraints between beta.101 and beta.103 (JSON wire format unchanged, verified by scoped diff).

## [0.1.2]

- Updated managed binary support and compatibility coverage to `@effect/tsgo` 0.24.3 with `effect` 4.0.0-beta.101; the platform-package layout, TypeScript backend matching, and `typescript@7.0.2` stable pairing are unchanged (npm tarballs still ship binaries without executable bits, so the plugin's permission restoration on extraction remains required).
- Added `missingPipeableSignature`, `preferSchemaTypeProperty`, `schemaOpaqueInstanceMember`, and `syncToSucceed` to Effect diagnostic directive completion, with real-binary coverage for the on-by-default `syncToSucceed` (quick fix) and `schemaOpaqueInstanceMember` diagnostics.
- Note for Effect v4 projects: `@effect/tsgo` 0.22.0+ reports `schemaOpaqueInstanceMember` at `error` severity by default; see the troubleshooting guide for downgrade options.
- Retargeted the build to the WebStorm 2026.2.0.1 stable platform build (262.8665.341), re-enabled Settings search indexing (`buildSearchableOptions`) now that the compile target no longer carries an EAP time-bomb, and moved plugin verification to WebStorm 2026.2.0.1 plus IntelliJ IDEA Ultimate 2026.2.1 EAP (262.9437.22).
- Adapted the JCEF tracer tab to the 2026.2 stable platform, where JCEF moved from platform classes into the separate bundled `com.intellij.modules.jcef` plugin: the dependency is declared optional, and the capability gate now also treats a missing or disabled JCEF plugin as unsupported (falling back to the Swing tracer).
- Updated the upstream reference pins, canary notes, and Dev Tools protocol documentation for the Effect v4 move to the `Effect-TS/effect` repository (devtools wire schema verified unchanged between beta.97 and beta.101).

## [0.1.1]

- Updated managed binary support and compatibility coverage to `@effect/tsgo` 0.19.0 with `effect` 4.0.0-beta.97, including complete platform-package extraction, TypeScript stable/nightly backend matching, native TypeScript package discovery, legacy `lib/tsgo` fallback, and actionable mismatch errors.
- Reworked the Effect Settings page into a scrollable, grouped form with mode-specific binary fields, naturally sized multiline editors, wrapped status text, and collapsed advanced options.
- Added Settings-page synchronization of the current form values to the `@effect/language-service` entry in `tsconfig.json`, using targeted JSON PSI edits that preserve JSONC comments, formatting, unrelated compiler options, other plugins, and manually managed Effect keys.
- Added JetBrains-side support and completion for Effect diagnostic directive comments before LSP diagnostics become editor annotations, with updated real-binary diagnostic and code-action coverage including `flatMapToMap`.
- Fixed Effect v4 metrics, debugger instrumentation, failed-span decoding, and Layer Mermaid preview handling against the upstream runtime wire shapes.
- Added Effect construct gutter markers, `egen`/`eservice`/`elayer`/`eschema` live templates, and tracer export to JSON.
- Changed plugin metadata to require an IDE restart for install, update, and uninstall so LSP and tool-window extension points are registered on startup.
- Documented local install checks, Effect language-service option ownership, upstream reference revisions, diagnostic behavior, and restart troubleshooting.
- Fixed cross-platform binary permission tests on Windows and isolated Qodana's nested Gradle project cache in CI to avoid project-cache lock contention.

## [0.1.0]

- Retargeted development and compatibility metadata to the WebStorm 2026.2 EAP / 262 platform line.
- Added the `dev.effect.intellij` plugin scaffold on the WebStorm 2026.2 EAP baseline.
- Added project-scoped Effect settings, managed/manual binary resolution, and direct `@effect/tsgo --lsp --stdio` launch wiring.
- Added the `Effect Dev Tools` runtime tool window with client, metrics, tracer, and adapted debug surfaces.
- Added a capability-gated JCEF tracer tab alongside the Swing tracer fallback.
- Added opt-in Node.js run/debug instrumentation injection through JetBrains' JavaScript run-configuration extension point.
- Added best-effort debugger snapshots for Effect Context, Span Stack, Fibers, and Breakpoints, plus pause-on-defect and current-fiber interrupt actions.
- Added a capability-gated local Mermaid graph action and a repo-local `@effect/tsgo` execute-command canary for `_effectGetLayerMermaid`.
- Added a canonical documentation set for setup, usage, troubleshooting, and development guidance.
- Added Marketplace publication readiness docs, MIT license, privacy disclosure, third-party notices, plugin icon, and reference-source provenance.
- Changed the first-run binary default to `MANUAL` and added npm tarball integrity checks for managed binary downloads.
- Constrained v1 compatibility metadata to the `262.*` WebStorm/IntelliJ Platform line.
- Removed checked-in reference subtrees and generated IntelliJ Platform cache files from the publication-ready source tree.
