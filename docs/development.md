# Development Guide

## Repository Layout

Use these top-level docs as the main entry points:

- [README](../README.md)
  - public landing page and Marketplace-description source
- [docs/README](README.md)
  - canonical long-form user documentation
- [specs/README](../specs/README.md)
  - implementation spec package and handoff history

## Core Commands

```bash
./gradlew build
./gradlew test
./gradlew check
timeout 90s ./gradlew runIde
./gradlew verifyPlugin
```

The repository also carries a real-binary probe script for `@effect/tsgo`:

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/native/tsgo
```

## Documentation Maintenance Rules

- Keep the README plugin-description block short and Marketplace-safe.
- Put detailed setup, usage, and troubleshooting material under `docs/`.
- Keep capability wording aligned with the implementation:
  - `Implemented`
  - `Adapted`
  - `Deferred`
  - `Pending evidence`
- Keep debugger wording as best-effort unless there is recorded paused-session smoke evidence.
- Keep Mermaid execute-command wording tied to the local canary until that bridge is published in
  `@effect/tsgo`.
- Keep support statements aligned with the current target baseline:
  - WebStorm `2026.2` EAP / `262.*`
  - IntelliJ IDEA Ultimate `2026.2` EAP / `262.*`
  - no Community Edition or Android Studio support claims

## Specs And Source Of Truth

The most important implementation references in this repo are:

- [specs/PLAN.md](../specs/PLAN.md)
- [specs/DESIGN.md](../specs/DESIGN.md)
- [specs/RESEARCH.md](../specs/RESEARCH.md)

When docs drift from code, prefer fixing the docs to match shipped behavior unless the review turns
up a genuine implementation bug that should be corrected separately.

## Local `@effect/tsgo` Canary

The plugin's managed `LATEST` and `PINNED` modes resolve published npm builds. To exercise repo-local
server patches, build the subtree and point `MANUAL` binary mode at the generated executable:

```bash
(cd .repos/effect-tsgo && bash _tools/setup-repo.sh --ci && pnpm run build)
```

The expected local binary path is `.repos/effect-tsgo/tsgo`. This is the route for validating
the `_effectGetLayerMermaid` execute-command bridge before it exists in a published package.

## Current Verification Posture

The current repository posture is intentionally honest:

- build, test, and Plugin Verifier coverage are part of normal validation
- WebStorm sandbox boot is exercised through `runIde`
- some supported-IDE manual editor smoke remains follow-up work
- the plugin is Marketplace-valid in descriptor/verifier terms, but documentation should not imply
  a feature is proven beyond the evidence currently in the repo

## Changelog And Release Notes

`CHANGELOG.md` should track shipped user-visible changes and major documentation shifts that matter
to consumers of the repository. Avoid stuffing it with routine wording edits unless they materially
change user guidance.
