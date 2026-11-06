# Privacy

Effect TSGO for JetBrains has no analytics or telemetry collection and does not transmit, sell, or share
telemetry with the plugin authors.

## Network Access

The plugin contacts `https://registry.npmjs.org` for package metadata when you select a managed binary mode:

- `LATEST` requests `@effect/tsgo` metadata to resolve the `latest` dist-tag, then requests metadata and,
  when installation is needed, the archive for the matching platform package.
- `PINNED` requests metadata and the archive for the configured platform package version when
  installation is needed. A healthy cached installation is reused.
- `MANUAL` uses the executable path you provide and does not contact npm.

Archives are downloaded from the `dist.tarball` URL supplied by npm metadata, which may point to npm
or another tarball host. The HTTP client follows redirects, so requests may also reach redirect
destinations. These endpoints receive standard HTTP request and connection metadata, including the
client IP address, HTTP headers, and the requested URL identifying the package, version, and platform.
These are package-download requests, not telemetry; the plugin does not attach project source code,
runtime traces, debug values, or analytics payloads to them. Downloaded npm archives are validated
against npm integrity metadata before extraction.

## Local Runtime Data

The Effect Dev Tools runtime server is a local WebSocket server, started explicitly from the IDE.
It binds only to the system loopback address (`InetAddress.getLoopbackAddress()`), with the port from
the project's Effect settings (default `34437`). The bind address is fixed to loopback; the port is
configurable. It does not listen on external network interfaces.
Runtime clients may send metrics, spans, tracer events, and debug-oriented snapshots to that local server.
Those values remain on your machine unless another local tool or process reads them.
Traces and debug values can contain application data. The plugin does not upload them to the authors.

## Instrumentation

Node.js instrumentation is opt-in. When enabled, the plugin can add bundled instrumentation to supported
Node.js run/debug configurations so local Effect runtime data can be observed in the IDE.

## Local Storage

Project settings are stored in JetBrains project configuration storage. Managed binaries are cached under
the JetBrains IDE system cache directory in an `effect-tsgo` folder by default; an application-level
binary cache directory override can change that location.

Only when you invoke the tracer export action, the plugin writes the active client's captured span tree
and client metadata to a JSON file under the IDE system cache's `effect-tsgo/tracer/` directory and opens
it in the editor. These exports persist on disk; the plugin does not automatically upload them.

When you choose to use tsrs for WebStorm's TypeScript service, the plugin generates a local TypeScript
package under the IDE system cache's `effect-tsgo/webstorm-typescript/` directory and points WebStorm's
TypeScript setting at it.
