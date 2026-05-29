# Changelog

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
