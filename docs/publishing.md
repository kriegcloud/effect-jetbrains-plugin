# Publishing

This guide tracks the release path for the official JetBrains Marketplace listing.

## Release Posture

- First upload: hidden default-channel release.
- Compatibility: WebStorm/IntelliJ Platform `262.*`.
- Verifier targets: WebStorm `262.6653.15` and IntelliJ IDEA Ultimate from `pluginVerifierIntelliJIdeaVersion`.
- License: MIT.
- Binary default: `MANUAL`; managed npm downloads are available only after user configuration.
- Branding: use the Effect name and mark only after Effect maintainer approval.

## Owner Setup

The first Marketplace upload must be done manually from the JetBrains Marketplace UI. Automated Gradle
publishing works only after the plugin exists in Marketplace.

Before upload:

1. Create or select the official Effect vendor profile in JetBrains Marketplace.
2. Accept the JetBrains Marketplace Developer Agreement.
3. Declare the required trader/non-trader status for the vendor profile.
4. Generate a permanent Marketplace token and save it as `PUBLISH_TOKEN` in GitHub Actions secrets.
5. Generate the plugin signing certificate/private key and save:
   - `CERTIFICATE_CHAIN`
   - `PRIVATE_KEY`
   - `PRIVATE_KEY_PASSWORD`
6. Confirm the public repository is the official `Effect-TS/effect-jetbrains-plugin` repository or update
   `pluginRepositoryUrl` before publishing.

## Local Release Build

Do not upload an old ZIP from `build/distributions`. Always rebuild from the release commit.

```bash
./gradlew clean check buildPlugin verifyPlugin qodanaScan --no-daemon --stacktrace
```

With signing secrets available in the environment:

```bash
./gradlew signPlugin --no-daemon --stacktrace
```

Upload the signed ZIP from `build/distributions` in the JetBrains Marketplace UI for the first hidden
release. Later releases can use the GitHub release workflow, which calls `./gradlew publishPlugin`.

## Marketplace Listing Checklist

- Plugin name: `Effect TSGO`
- Plugin ID: `dev.effect.jetbrains`
- License: MIT
- Privacy link: repository `PRIVACY.md`
- Source link: official GitHub repository
- Issue tracker: official GitHub issues
- Documentation: repository README and `docs/`
- Tags: TypeScript, JavaScript, Effect, LSP, Dev Tools
- Screenshots:
  - Settings | Tools | Effect with `MANUAL` selected
  - LSP widget running against a real `tsgo` binary
  - Effect Dev Tools Clients/Metrics/Tracer tabs
  - Debug tab in its honest best-effort state

## Effect Discord Message

```text
Hey Effect team! I have been building a JetBrains/WebStorm plugin for @effect/tsgo and Effect Dev Tools parity, inspired by the existing VS Code and Zed extensions.

The goal is to publish it on the JetBrains Plugin Marketplace as a free MIT-licensed plugin. It currently targets WebStorm 2026.2 EAP, starts with manual @effect/tsgo binary configuration by default, and keeps managed npm downloads opt-in.

Before I use the Effect name/logo or publish anything public, I wanted to ask if this is okay with the team. I am also happy to transfer the GitHub repo, Marketplace ownership, or both to the Effect org whenever you feel ready. I can keep the first Marketplace upload hidden while review and ownership details get sorted.
```

## Official References

- https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
- https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
- https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html
- https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
- https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html
- https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html
- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/marketplace/hidden-plugin.html
