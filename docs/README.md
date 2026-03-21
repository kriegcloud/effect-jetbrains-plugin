# Documentation

This directory is the canonical documentation set for the shipped plugin behavior.

Use these pages in this order:

- [Getting started](getting-started.md)
  - Installation, prerequisites, binary modes, first-run setup, and the initial LSP/runtime flow
- [Usage guide](usage.md)
  - Day-to-day settings, widget behavior, `Effect Dev Tools`, and the current debugger story
- [Troubleshooting](troubleshooting.md)
  - Binary failures, runtime/server issues, status meanings, logs, and known evidence gaps
- [Development guide](development.md)
  - Build, test, verifier, docs maintenance, and the spec set used by contributors

## Documentation conventions

These docs use the following capability language consistently:

- `Implemented`
  - Shipped behavior that exists in the current plugin
- `Adapted`
  - Shipped behavior that intentionally maps the VS Code reference into JetBrains-native UX
- `Deferred`
  - Planned or discussed behavior that is not currently implemented
- `Pending evidence`
  - Behavior that is expected or supported by design, but still lacks the final manual or
    environment-specific verification record called out in repo handoffs

For the repository landing page and Marketplace-safe summary, start with the top-level
[README](../README.md).
