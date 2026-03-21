# Effect TSGO for JetBrains

<!-- Plugin description -->
JetBrains plugin for `@effect/tsgo` language-server parity and Effect runtime Dev Tools.
It is built on the `2025.3.x` platform baseline, launches `@effect/tsgo` directly with
`--lsp --stdio`, and ships in staged milestones: core LSP parity first, runtime Dev Tools
second, and adapted debugger surfaces behind explicit opt-in and capability checks.
<!-- Plugin description end -->

## Current Status

- Foundation, direct-binary LSP wiring, and runtime `Effect Dev Tools` surfaces are implemented.
- Runtime metrics are polled from connected clients; tracer data is streamed from runtime span events.
- Debug tabs currently provide attach/setup guidance for the active JetBrains debug session. Live
  context, span, fiber, and breakpoint snapshots remain deferred.
- Optional JCEF tracer work and local Mermaid preview transport remain deferred.

## Verification

```bash
./gradlew check build
timeout 90s ./gradlew runIde
./gradlew verifyPlugin
```

`runIde` is currently exercised against the WebStorm `2025.3.3` target because that is the
verified local bootstrap path for the plugin sandbox. IntelliJ IDEA Ultimate compatibility is
still a manual smoke-test item rather than a completed verification claim, and the richer
Milestone 2 editor-behavior parity checks still need explicit real-IDE smoke evidence.

## Development

```bash
./gradlew build
./gradlew check
./gradlew runIde
```
