# Third-Party Notices

This project is [MIT licensed](LICENSE). The plugin ZIP includes the following third-party runtime
JARs as separate libraries under `effect-jetbrains-plugin/lib/`. Direct dependencies are declared in
`build.gradle.kts` and `gradle/libs.versions.toml`; the table includes their runtime transitive
dependencies. Versions were cross-checked against the existing local `0.1.10` distribution ZIP;
that artifact is inventory evidence, not verification of a newly built or signed release.

## Bundled JVM Libraries

| Runtime JAR | License | Source / purpose |
| --- | --- | --- |
| `Java-WebSocket-1.6.0.jar` | MIT | [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0), local Dev Tools server |
| `commons-compress-1.28.0.jar` | Apache-2.0 | [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), npm tarball extraction |
| `commons-codec-1.19.0.jar` | Apache-2.0 | [Apache Commons Codec](https://commons.apache.org/proper/commons-codec/), transitive dependency |
| `commons-io-2.20.0.jar` | Apache-2.0 | [Apache Commons IO](https://commons.apache.org/proper/commons-io/), transitive dependency |
| `commons-lang3-3.18.0.jar` | Apache-2.0 | [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/), transitive dependency |
| `jackson-annotations-2.22.jar` | Apache-2.0 | [Jackson Annotations](https://github.com/FasterXML/jackson-annotations), JSON annotations |
| `jackson-core-2.22.4.jar` | Apache-2.0 | [Jackson Core](https://github.com/FasterXML/jackson-core), JSON streaming |
| `jackson-databind-2.22.4.jar` | Apache-2.0 | [Jackson Databind](https://github.com/FasterXML/jackson-databind), JSON mapping |
| `slf4j-api-2.0.13.jar` | MIT | [SLF4J](https://www.slf4j.org/), transitive logging facade |

The Commons and Jackson JARs retain their upstream `META-INF/LICENSE*` and `META-INF/NOTICE*`
files. Jackson Core additionally retains `FastDoubleParser-LICENSE`, `FastDoubleParser-ThirdParty-LICENSE`,
and `Schubfach-LICENSE` under `META-INF/`; those texts cover its embedded components. SLF4J retains
`META-INF/LICENSE.txt`. Preserve these upstream notices when distributing the separate JARs.
Test dependencies and Gradle/build plugins are not bundled runtime libraries. The IDE supplies the
IntelliJ Platform and Kotlin runtime; they are not additional third-party JARs in this ZIP inventory.

### Java-WebSocket MIT notice

Java-WebSocket 1.6.0 does not carry a license file in its JAR. Its
[upstream license](https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/LICENSE) is reproduced here:

```text
Copyright (c) 2010-2020 Nathan Rajlich

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:
The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## Downloaded Binary Packages

The `@effect/tsgo` native binaries are **not redistributed in the plugin ZIP**. Managed `LATEST` and
`PINNED` modes download platform packages from npm at runtime under their own licenses. The Effect
packages declare MIT (for example,
[`@effect/tsgo-linux-x64@0.67.4` metadata](https://registry.npmjs.org/@effect%2ftsgo-linux-x64/0.67.4));
this project's MIT license does not replace the downloaded packages' licenses or those of their
embedded components, such as TypeScript, tsrs, and Oxlint. Consult the selected package version's
upstream license and notice files for those components. Versions depend on the selected mode and pin.

The plugin validates npm integrity metadata before extracting the package, including any upstream
license files, under its managed binary cache (`effect-tsgo/` in the IDE system cache by default, or
the configured binary cache override). See [Privacy](PRIVACY.md) for download and local-storage behavior.
