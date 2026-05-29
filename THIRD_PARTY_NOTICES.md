# Third-Party Notices

This project is MIT licensed. The plugin distribution also includes third-party libraries required for
runtime behavior.

## Bundled JVM Libraries

| Component | License | Purpose |
| --- | --- | --- |
| Java-WebSocket | MIT | Local Effect Dev Tools WebSocket server |
| Apache Commons Compress | Apache-2.0 | npm tarball extraction |
| Apache Commons Codec | Apache-2.0 | Transitive Apache Commons dependency |
| Apache Commons IO | Apache-2.0 | Transitive Apache Commons dependency |
| Apache Commons Lang | Apache-2.0 | Transitive Apache Commons dependency |
| Jackson Annotations/Core/Databind | Apache-2.0 | JSON parsing and serialization |
| SLF4J API | MIT | Logging facade dependency |

## Downloaded Binary Packages

Managed binary modes download `@effect/tsgo` platform packages from npm. Those packages are published by
the Effect project and are MIT licensed at the time this plugin release is prepared.

The plugin validates npm integrity metadata for downloaded archives before extraction.
