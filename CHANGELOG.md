# Changelog

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
