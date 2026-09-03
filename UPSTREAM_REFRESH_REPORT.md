# Upstream Refresh Report — 2026-08-27

Branch: `refresh/tsgo-0.37.0-effect-rc112`

Plugin release build: `0.1.5`

The run started from current `origin/main` at merge commit
`a88a9ab03941b080b7761ba286c9e6de94f1261d`. A pre-existing uncommitted threading fix in
`EffectTsconfigSyncService.kt` was preserved, reviewed, and carried as a distinct implementation
change. The six ignored reference clones were clean before refresh and remain clean, detached, and
exactly pinned after fetch/prune/tag synchronization.

## Reference inventory

| Reference | Previous pin | Current pin | Commits | Classification |
| --- | --- | --- | ---: | --- |
| Effect tsgo | `ca311a5c071e6a1c9f91f259c5373adc43dc6031` | `1ab43807aa20595e83df4a8c5a73b7e8ae7e2e3d` | 20 | Validation, regression test, and documentation |
| Effect v4 | `7018f966847e7b8133e3243bd1bd42525bdc89f1` | `e72b12fc305710550bc6dcb978e92de8abff88cd` | 169 | Validation and documentation |
| Effect VS Code extension | `64631d41a75770149361703581e923cf6971d5f4` | unchanged | 0 | Not applicable |
| Zed Effect tsgo extension | `0c4f302c861359b4f9d23f58ac146101030c6229` | unchanged | 0 | Not applicable |
| Effect language service | `5e4d380b6fcd20f048dd8d41515bcd9ea47ffda4` | unchanged | 0 | Not applicable |
| IntelliJ Platform Plugin Template | `7002f57406739f166d0fcf97d23e699a2c4e17dc` | unchanged | 0 | Not applicable |

Refresh method: each clone fetched `origin` with prune and tags, then checked out the recorded
`origin/main` commit detached. No clone used `git pull`; no dirty clone was reset.

## Delta analysis and decisions

### Effect tsgo

The published kickoff target is `@effect/tsgo@0.37.0`, tag commit
`2317cf4087dacdd3ea0c022856c19c70bf69858f`, with the recorded source pin seven commits ahead.

- **Validate and test:** the platform manifest advances from schema 4 to schema 5. TypeScript
  components add `provider` (`typescript-go` for stable and `typescript` for next). The plugin's
  shape-based component parser correctly ignores that extra metadata while retaining exact
  TypeScript `gitHead` selection, so no production resolver branch is required. The synthetic
  schema-5 regression now models both real provider values.
- **Validate package layout:** the linux-x64 tarball still ships stable TypeScript `7.0.2` at
  `artifacts/typescript/7.0.2/tsc`, plus a byte-identical `lib/tsc`. Both are executable mode 0755,
  size 30,265,506, and SHA-256
  `881b1b0c1e5d5dbd2cb20c3760373053dbba3d7ffc70fbbebd53ef18ef000382`.
- **Document:** 0.37.0 supports the migrated TypeScript compiler provider, hardens Nix setup, and
  suppresses unsafe pipe-chain rewrites when overload arity differs.
- **Defer until published:** `allOfMapToForEach`, `mapSomeToAsSome`, `catchDieToOrDie`, and
  `catchConditionalRefailToCatchIf` exist only in the seven post-tag commits. They are not asserted
  against the 0.37.0 binary.
- **No change:** the pin still has no `_effectGetLayerMermaid` command or layer-graph
  `workspace/executeCommand`; hover Mermaid decoding remains the supported path.
- **Out of scope:** Oxlint and tsgolint remain package artifacts only. The IDE does not assume
  ownership of their configuration or process lifecycle.

### Effect v4

The verifier target advances from `effect@4.0.0-rc.108` to exact release `4.0.0-rc.112`; the source
pin is 31 commits past that tag.

- No rc.108-to-rc.112 file under `packages/effect/src/unstable/devtools/` changed.
- The only release-scoped observability change is shared OTLP tracer hexadecimal ID generation.
- Across the old-to-new main pins, one additional observability change standardizes OTLP Config
  constructor naming.
- Neither change alters the DevTools JSON wire shapes decoded by the plugin. Debugger, metrics, and
  tracer source changes are therefore not justified; the real-binary smoke is the required proof.

### Unchanged references

The four unchanged repositories provide no new implementation signal. There is no new VS Code
debugger hardening to port, no Zed launcher delta, no legacy language-service diagnostic delta, and
no IntelliJ template build or publication delta.

## Implemented change set

1. Updated component-manifest documentation and tests for the real schema-5 provider shape; kept
   production parsing fail-closed and exact-head based.
2. Updated verifier dependencies and provenance to `@effect/tsgo@0.37.0`, TypeScript `7.0.2`, and
   Effect `4.0.0-rc.112`.
3. Preserved the existing tsconfig synchronization fix: PSI/VFS/document reads execute inside a read
   action, while document commit and mutation execute inside the write command.
4. Bumped the test build to plugin version `0.1.5` and added matching changelog notes.
5. Added project skill `.agents/skills/effect-upstream-refresh/SKILL.md`, exposed through project
   `.claude/skills/` and `.codex/skills/` symlinks. New sessions can invoke
   `/effect-upstream-refresh` in Claude Code or `$effect-upstream-refresh` in Codex.
6. Updated `docs/reference-sources.md`, `docs/usage.md`, and `docs/development.md`. The parity matrix
   remains intentionally unchanged until the user completes manual IDE smoke.

## Verification evidence

### Focused tests

```bash
./gradlew test \
  --tests 'dev.effect.intellij.binary.EffectBinaryServiceTest' \
  --tests 'dev.effect.intellij.settings.EffectSettingsValidationTest.testSettingsSyncValidatesAndUsesUnappliedFormSnapshot' \
  --no-daemon --stacktrace
```

Result: **passed**.

### Published native binary

```bash
node scripts/verify-real-tsgo-lsp.mjs \
  --binary /tmp/.../package/artifacts/typescript/7.0.2/tsc
```

Result: **all four lanes passed** against `@effect/tsgo@0.37.0`, `typescript@7.0.2`, and
`effect@4.0.0-rc.112`.

- Healthy workspace: Mermaid hover link present, 44 completion items, expected document/workspace
  symbols, and no advertised execute commands.
- Failing workspace: `missingStarInYieldEffectGen` plus `Replace yield with yield*`.
- Diagnostic workspace: all recorded diagnostics and quick fixes, including typed Schema decode and
  floating-Effect `yield*` coverage.
- Directive workspace: next-line and region suppression/re-enable behavior passed.

### Release command

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

Result: **passed** on the post-review code head in 2m45s.

- Qodana: **0 problems detected**.
- Plugin Verifier: **Compatible** with WebStorm `WS-262.8665.341` and IntelliJ IDEA Ultimate
  `IU-262.9437.22`.
- Informational verifier output: 35 known deprecated JetBrains LSP API usages on each target; zero
  compatibility errors.
- `timeout --signal=INT --kill-after=10s 90s ./gradlew runIde --no-daemon --stacktrace`: sandbox IDE
  launched and exited successfully in 18 seconds.

### Distribution and local install

- ZIP: `build/distributions/effect-jetbrains-plugin-0.1.5.zip`
- Size: 5,214,962 bytes
- SHA-256: `84b317e4c8ba3b73cbb3e9c0f7d18d0b51d4420a662ee69490b3f1ac7b3e6151`
- ZIP integrity: no compressed-data errors
- Install: the pre-review 0.1.5 build was installed while WebStorm was closed at
  `~/.local/share/JetBrains/WebStorm2026.2/effect-jetbrains-plugin`. The post-review ZIP above was
  **not** installed because WebStorm had since been started; the installer failed closed and no IDE
  process was terminated.
- Installed pre-review descriptor: `dev.effect.jetbrains`, `Effect TSGO`, version `0.1.5`, build
  range `262`–`262.*`

## Manual evidence still owed

After starting WebStorm, confirm the plugin is enabled and exercise managed `LATEST` resolution,
LSP startup, diagnostics/quick fixes, the Effect DevTools tracer against rc.112, and hover Layer
Mermaid preview. Do not update `docs/parity-matrix.md` until that user-run evidence exists.

No Marketplace publish, GitHub release, PR merge, or user IDE termination is authorized by this
refresh.
