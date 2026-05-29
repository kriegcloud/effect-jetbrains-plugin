# Privacy

Effect TSGO for JetBrains does not collect, transmit, sell, or share telemetry with the plugin authors.

## Network Access

The plugin can contact `https://registry.npmjs.org` only when you select a managed binary mode:

- `LATEST` resolves the current `@effect/tsgo` npm dist-tag and downloads the matching platform package.
- `PINNED` downloads the configured `@effect/tsgo` platform package version.
- `MANUAL` uses the executable path you provide and does not contact npm.

Downloaded npm archives are validated against npm integrity metadata before extraction.

## Local Runtime Data

The Effect Dev Tools runtime server listens on a local loopback port only when you start it from the IDE.
Runtime clients may send metrics, spans, tracer events, and debug-oriented snapshots to that local server.
Those values remain on your machine unless another local tool or process reads them.

## Instrumentation

Node.js instrumentation is opt-in. When enabled, the plugin can add bundled instrumentation to supported
Node.js run/debug configurations so local Effect runtime data can be observed in the IDE.

## Local Storage

Project settings are stored in JetBrains project configuration storage. Managed binaries are cached under
the JetBrains IDE system cache directory in an `effect-tsgo` folder.
