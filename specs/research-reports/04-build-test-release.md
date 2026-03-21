# Research Report: Build, Test, and Release

## Summary
The local JetBrains template already contains most of the infrastructure needed for a production plugin: Gradle 2.x plugin setup, signing, publishing, verifier support, and test framework wiring. The missing work is to tailor it for a multi-surface plugin that manages a language-server binary and possibly a JCEF tracer UI.

## Template findings

### Build stack
- Kotlin + Java enabled
- IntelliJ Platform Gradle Plugin 2.x
- Changelog plugin
- Qodana
- Kover
- Plugin verifier through `pluginVerification`

### Test support
- `TestFrameworkType.Platform`
- sample unit/integration test scaffolding
- dedicated UI test run configuration with robot server

### Packaging and release
- signing via `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`
- publishing via `PUBLISH_TOKEN`
- version-driven release channels

## Recommended project conventions

### Language and runtime
- Kotlin for plugin code
- JVM toolchain aligned with the target platform
- avoid bundling duplicate Kotlin or coroutines libraries

### Packaging
- keep the plugin ZIP lean
- download large runtime assets on demand when practical
- avoid shipping every `@effect/tsgo` platform binary inside the plugin ZIP unless offline use is a hard requirement
- if a JCEF tracer panel ships, keep a browser-less fallback so the plugin still works when `JBCefApp.isSupported()` is false

### Binary strategy
Preferred rollout:

1. Auto-managed npm package download into a plugin-managed cache directory
2. Version pinning support
3. Manual binary path fallback

Avoid:
- patching IDE binaries
- coupling the plugin to project-local `node_modules`

## Test matrix recommendation

### IDE matrix
- WebStorm, latest supported `2025.3.x`
- IntelliJ IDEA Ultimate, latest supported `2025.3.x`

### OS matrix
- macOS arm64
- Linux x64
- Windows x64

### Functional matrix
- LSP startup and reconnect
- diagnostics rendering
- quick-fix/code-action application
- hover
- completion
- layer graph links in hover
- document symbols / structure
- inlay hints
- settings persistence
- version pinning and manual path override
- Dev Tools server start/stop
- tracer, metrics, and client views
- no-JCEF tracer fallback
- JCEF-backed tracer panel when supported
- debug-session-bound views
- plugin verifier

## Required fixtures
- small TypeScript project with `@effect/language-service` defaults
- fixture with custom diagnostic severities
- fixture with multiple diagnostics and code actions
- fixture for Effect layer-mermaid expectations
- fixture app that emits Dev Tools spans and metrics
- fixture debug app for fibers/context/breakpoints

## Recommendation
Start from the template, but update the target platform baseline and add a dedicated fixture/test strategy for both LSP and Dev Tools parity before implementation begins.

## Source checkpoints
- `.repos/intellij-platform-plugin-template/build.gradle.kts`
- `.repos/intellij-platform-plugin-template/gradle.properties`
- `.repos/intellij-platform-plugin-template/src/main/resources/META-INF/plugin.xml`
