# Development Guide

## Repository Layout

Use these top-level docs as the main entry points:

- [README](../README.md)
  - public landing page and Marketplace-description source
- [docs/README](README.md)
  - canonical long-form user documentation
- [Publishing](publishing.md)
  - Marketplace release process, owner setup, signing, and first-upload checklist
- [Reference sources](reference-sources.md)
  - upstream reference repos and canary provenance
- [specs/README](../specs/README.md)
  - implementation spec package and handoff history

## Core Commands

```bash
./gradlew build
./gradlew test
./gradlew check
timeout 90s ./gradlew runIde
timeout 90s ./gradlew runIdeVerifierWebStorm
./gradlew verifyPlugin
```

`runIde` boots the sandbox on the stable compile target (`platformVersion`), while
`runIdeVerifierWebStorm` boots it on the pinned WebStorm EAP (`pluginVerifierWebStormVersion`).
A `timeout`-bounded boot kills Gradle but leaves the sandbox IDE's lock files behind
(`.intellijPlatform/sandbox/effect-jetbrains-plugin/<build>/{config,config_*}/.lock` and
`{system,system_*}/.port`). The next headless IDE run, such as `buildSearchableOptions`, then fails
with `DirectoryLock$CannotActivateException` / exit code 6. Confirm no sandbox IDE process is running
and delete those files before the release command. Every sandbox boot also gets its own
`XDG_CONFIG_HOME` under `build/sandbox-xdg/config`, so the IDE's `plasmashell --version` desktop
probe cannot touch the host Plasma configuration.
Plugin Verifier targets WebStorm stable `262.10315.144`, WebStorm 2026.3 EAP `263.4732.34`, and
IntelliJ IDEA Ultimate 2026.3 EAP `263.4732.28` (`pluginVerifierIntelliJIdeaVersion`). The declared
`gradleVersion` matches the `9.7.1` wrapper.

EAP verifier pins must track the newest build: bundled libraries can change within a platform line.
The move to `263.4732.x` exposed lsp4j 1.0.0's `Diagnostic.getMessage()` return-type change from
`String` to `Either<String, MarkupContent>`, which broke 0.1.6 diagnostic highlighting despite the
existing `263.*` compatibility range. Version 0.1.7 resolves diagnostic message getters/setters
reflectively, with unit cases for both shapes. Keep the stable compile target and exercise both
stable and EAP compatibility when changing these accessors. The separate `LspServer*` → `LspClient*`
migration remains issue #26.

All sandbox `RunIdeTask` instances use `build/sandbox-xdg/config` as `XDG_CONFIG_HOME`, so the
platform's `plasmashell --version` desktop probe does not try to write the host Plasma configuration
when the host home is read-only.

The repository also carries a real-binary probe script for `@effect/tsgo`:

```bash
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/native/tsc
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/native/tsc --only new-diagnostics
node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/native/tsc --only diagnostic-directives
```

The verifier copies its fixtures to temporary directories and installs the validated
`typescript@7.0.2` package (`gitHead` `2bd066d87f5bafd315be9f40889d0a60b9e58e0b`) plus
`effect@4.0.0-rc.115`. The recorded published native target is `@effect/tsgo@0.45.0`. It does not
install `@effect/language-service`: that string is the `compilerOptions.plugins[].name` consumed by
the language service already compiled into `@effect/tsgo`.

The new cases check `obsoleteSchemaImport` warning severity and suppression-only actions, apply
`preferSucceedSomeOrNone` / `allOfMapToForEach` edits and verify that the findings clear without new
errors, and enable default-off `schemaSync` for exactly one line. The rule-name snapshot at
`src/test/testData/tsgo/rule-names-0.45.0.json` comes from release tag `54bbc1e7`; a Kotlin test checks
completion against all 113 names. `catchIfTagToCatchTag`, `flatMapIgnoredParamToAndThen`, and
`catchRefailToTapError` are post-tag source rules and remain excluded from that snapshot and
published-binary claims.

The runtime companion probe exercises the plugin's actual injected instrumentation:

```bash
node scripts/verify-instrumentation.mjs
node scripts/verify-instrumentation.mjs --effect 4.0.0-rc.115
```

By default it installs exact Effect v4 release candidates 112 and 115 in separate temporary
workspaces copied from [`smoke-app`](../src/test/testData/fixtures/runtime/smoke-app/). It checks
inner-to-outer span stacks, source locations, pause-on-defect reveal state, current/alive fibers,
and interruption. Structural controls exercise legacy v3/older-v4 fields and cache precedence;
the real rc.115 negative control proves the removed fields are absent. The `--effect` option takes
a comma-separated list of exact versions. Network access to npm is required; Node 22+ supplies the
global WebSocket used by the runtime fixture.

The same fixture app pins `effect@4.0.0-rc.115` and `typescript@7.0.2` for manual smoke, with
`index.mjs` (DevTools client, nested spans, metrics, periodic failures, defect loop, and an
interruptible `Effect.never` fiber), `example.ts`, and
[`SMOKE_CHECKLIST.md`](../src/test/testData/fixtures/runtime/smoke-app/SMOKE_CHECKLIST.md). To run it:

```bash
cd src/test/testData/fixtures/runtime/smoke-app
npm install --no-package-lock
npm start
```

Start the IDE's DevTools server on port `34437` first. For paused-session evidence, follow the
checklist's Node.js debug configuration and source-reveal steps. Node assertions and wire probes
do not replace the human editor/debugger pass; record that evidence before updating the parity
matrix.

Upstream `diagnostics --list-files` reports per-file Effect detection/support metadata. The upstream
rule metadata now marks `genericEffectServices` v3-only and `schemaSyncInEffect` v3 + v4, moves
`unsafeEffectTypeAssertion` to `correctness`, and keeps `schemaSync` off by default (the effect-native
preset enables it at warning). Oxlint and tsgolint remain upstream-managed package contents only;
the IDE does not configure or run them independently.

## Documentation Maintenance Rules

- Keep the README plugin-description block short and Marketplace-safe.
- Put detailed setup, usage, and troubleshooting material under `docs/`.
- Keep capability wording aligned with the implementation:
  - `Implemented`
  - `Adapted`
  - `Deferred`
  - `Pending evidence`
- Keep debugger wording as best-effort unless there is recorded paused-session smoke evidence.
- Keep Mermaid execute-command wording experimental until that bridge is published in `@effect/tsgo`.
- Keep support statements aligned with the current target baseline:
  - WebStorm `2026.2` stable and `2026.3` EAP / `262.*`–`263.*`
  - IntelliJ IDEA Ultimate `2026.2`–`2026.3` EAP / `262.*`–`263.*`
  - no Community Edition or Android Studio support claims

## Specs And Source Of Truth

The most important implementation references in this repo are:

- [specs/PLAN.md](../specs/PLAN.md)
- [specs/DESIGN.md](../specs/DESIGN.md)
- [specs/RESEARCH.md](../specs/RESEARCH.md)

When docs drift from code, prefer fixing the docs to match shipped behavior unless the review turns
up a genuine implementation bug that should be corrected separately.

## External `@effect/tsgo` Canary

The plugin's managed `LATEST` and `PINNED` modes resolve published npm builds. To exercise unpublished
server patches, clone `Effect-TS/tsgo` outside this repository, build it there, and point
`MANUAL` binary mode at the generated executable:

```bash
git clone https://github.com/Effect-TS/tsgo ../effect-tsgo-canary
(cd ../effect-tsgo-canary && bash _tools/setup-repo.sh --ci && pnpm run build)
```

Use the resulting Effect-patched native binary path in `MANUAL` mode. Current published packages
(0.32.0+) ship per-version `tsc` executables under `artifacts/typescript/<version>/` with a `lib/tsc`
compatibility copy of the `latest` build; 0.19.0–0.31.x called their binaries `lib/tsc` and
`lib/tsc-next` (0.26.0 replaced their per-binary JSON metadata with `lib/upstream.json`), and older
source builds may still produce `tsgo`. Version 0.45.0 retains the schema-5 component manifest and
TypeScript provider metadata without changing those executable paths. This is the route
for validating the `_effectGetLayerMermaid` execute-command bridge before it exists in a published
package.

## Current Verification Posture

The current repository posture is intentionally honest:

- build, test, and Plugin Verifier coverage are part of normal validation
- WebStorm sandbox boot is exercised through `runIde`
- some supported-IDE manual editor smoke remains follow-up work
- the [refresh report](../UPSTREAM_REFRESH_REPORT.md) records actual command exits, verifier verdicts,
  sandbox limits, and the provisional distribution receipt; build targets alone are not proof of
  a compatible final artifact
- documentation should not imply a feature is proven beyond the evidence currently in the repo

## Changelog And Release Notes

`CHANGELOG.md` should track shipped user-visible changes and major documentation shifts that matter
to consumers of the repository. Avoid stuffing it with routine wording edits unless they materially
change user guidance.
