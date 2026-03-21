# Plan Slice: Foundation

## Objective
Turn the IntelliJ Platform template into the real `dev.effect.intellij` plugin scaffold so Milestone 2 can focus on shipping core LSP parity instead of backfilling project structure.

## Locked scope
- Rename template identifiers, packages, resources, and metadata to the final plugin identity.
- Upgrade the implementation baseline and build configuration to `2025.3.x`.
- Add the required plugin dependencies:
  - `com.intellij.modules.platform`
  - `com.intellij.modules.ultimate`
  - `com.intellij.modules.lsp`
- Create the package and service scaffold defined in `specs/DESIGN.md`.
- Add the fixture and test scaffold needed for later LSP, runtime, and debugger work.

## Ordered work
1. Rename plugin id, display name, package roots, bundle/resource names, icons, and template metadata to the Effect plugin identity.
2. Upgrade Gradle, IntelliJ Platform plugin settings, verifier targets, and any baseline properties to `2025.3.x`, while keeping `2025.2.2` only as the documented bootstrap floor.
3. Register the required plugin dependencies and ensure plugin metadata matches the locked supported IDE set.
4. Create the package layout for `actions`, `binary`, `debug`, `devtools`, `lsp`, `settings`, `status`, `ui`, and `webview`.
5. Register the application and project service scaffold named in `specs/DESIGN.md`, even if implementations remain stubs at this stage.
6. Add shared logging, notification, and constants helpers that later milestones can build on.
7. Create `src/test/kotlin` and `src/test/testData/fixtures/{lsp,devtools,debug}` with at least one minimal TypeScript sample workspace.

## Test expectations
- `build` and `check` pass on the locked baseline toolchain.
- `runIde` launches without template metadata errors.
- Lightweight smoke tests can instantiate the registered services and load plugin resources.
- The test harness can load fixtures from the new `src/test/testData/fixtures/...` layout.

## Fixture needs
- One minimal TypeScript workspace fixture for later LSP startup coverage.
- Placeholder runtime fixture directories for metrics and tracer snapshots.
- Placeholder debug fixture directories for paused-session snapshots.

## Exit criteria
- No template naming remains in user-visible metadata or package structure.
- The plugin builds against `2025.3.x` with the required dependencies enabled.
- The package and service scaffold matches `specs/DESIGN.md`.
- The repository is ready for fixture-driven implementation in later milestones.
