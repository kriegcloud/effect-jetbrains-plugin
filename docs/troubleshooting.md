# Troubleshooting

## Start With The Current Surface

When something goes wrong, check these in order:

1. The Effect LSP widget state
2. `Settings | Tools | Effect`
3. The `Effect Dev Tools` status banner and active client selection
4. The IDE log directory from the widget's `Logs` action

## Plugin Is Missing After Local Install

If `Settings | Tools | Effect`, the Effect LSP widget, or `Effect Dev Tools` is missing after
installing from disk:

1. Restart the IDE after installing or updating the plugin ZIP.
2. Open `Settings | Plugins | Installed` and confirm `Effect TSGO` is enabled.
3. If JetBrains Settings Sync is enabled, confirm it did not mark `dev.effect.jetbrains` disabled.
4. Confirm the plugin exists in the IDE plugin install directory, not only in the IDE plugin cache.

For local WebStorm smoke testing, close WebStorm and install the latest built ZIP directly:

```bash
scripts/install-local-webstorm-plugin.sh --product WebStorm2026.3
```

Without `--product`, the helper uses the config directory named by the JetBrains Toolbox WebStorm
install (`product-info.json` → `dataDirectoryName`) and falls back to `WebStorm2026.2`.

The helper unpacks the plugin into the local JetBrains plugin directory, removes stale
`effect-jetbrains-plugin*.zip` cache entries, removes the plugin from `disabled_plugins.txt` if
needed, and clears a local Settings Sync `enabled: false` marker for `dev.effect.jetbrains`.

The IDE log should list `Effect TSGO` under loaded custom plugins on startup. If it only shows a
cached `effect-jetbrains-plugin.zip`, install from disk again, apply the plugin change, and restart.

If the IDE reports that `Effect TSGO` "requires build 262.* or older" after a WebStorm upgrade, the
installed ZIP predates the current compatibility range: rebuild from a checkout whose
`pluginUntilBuild` covers the new IDE line (`263.*` for WebStorm 2026.3) and reinstall.

## Common LSP Issues

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Widget says `Not Configured` or manual mode will not apply | The default `MANUAL` mode has no valid executable path yet | Provide a valid Effect-patched native `tsc` or legacy `tsgo` path, or explicitly switch to a managed mode |
| Widget stays on `Resolving Binary` or moves to `Error` in `LATEST` or `PINNED` mode | The plugin cannot reach npm, the version is wrong, the npm integrity check failed, or your platform is unsupported by the published package | Recheck connectivity and the configured version, then restart the server |
| Managed mode reports that no compatible binary matches the workspace | The workspace has no native TypeScript package metadata, or its TypeScript `gitHead` matches none of the packaged TypeScript builds (`artifacts/typescript/<version>/tsc` since 0.32.0, `lib/tsc`/`lib/tsc-next` before that) | Install a supported `typescript >= 7`, `@typescript/native`, alias, or legacy native-preview version that matches the selected `@effect/tsgo` release; do not force an arbitrary binary |
| Manual mode will not apply | The path is blank, invalid, missing, not a file, or not executable | Provide a valid executable Effect-patched native `tsc` or legacy `tsgo` path |
| Widget reaches `Restart Required` | LSP-relevant settings changed | Use the widget restart action after applying settings |
| No LSP startup when a file opens | The file is not a supported TypeScript extension or the server failed before startup completed | Use a supported file and inspect the widget state plus logs |
| New `schemaOpaqueInstanceMember` errors after updating the managed binary | `@effect/tsgo` 0.22.0+ enables this Effect v4 rule at `error` severity by default; instance members on `Schema.Opaque` classes are now rejected | Move members off the opaque class, or downgrade/disable the rule via `diagnosticSeverity` in the tsconfig `@effect/language-service` plugin entry or a `// @effect-diagnostics schemaOpaqueInstanceMember:off` directive |

## Binary Mode Checks

### `LATEST`

- Requires network access to npm during version resolution and download
- Chooses the current `latest` dist-tag for `@effect/tsgo`
- Validates npm tarball integrity before extraction

### `PINNED`

- Requires a non-blank version string
- Uses the exact version you configured
- Requires network access to npm during download
- Validates npm tarball integrity before extraction

### `MANUAL`

- Requires an executable Effect-patched native `tsc` or legacy `tsgo` path
- Does not rely on the plugin-managed download/cache path

## Managed Cache Issues

Managed binaries are stored under the JetBrains system cache area in an `effect-tsgo` directory.

If a managed install looks stale or corrupted:

1. Close the IDE or stop using the project
2. Remove the `effect-tsgo` cache directory from the IDE system path
3. Reopen the project and let the plugin resolve the binary again

If the cache is intact but resolution reports a TypeScript compatibility mismatch, clearing it will
not change the result. Align the workspace's native TypeScript package with the selected
`@effect/tsgo` version instead.

## Runtime Dev Tools Issues

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Runtime server will not start | The configured port is unavailable or startup failed | Change the Dev Tools port in settings, then retry |
| `Clients` stays empty | No runtime client is connected to the local server yet | Start the runtime server and confirm the client is using the configured port |
| `Metrics` says no active client | No client is selected or connected | Use the toolbar client selector or connect a runtime client |
| `Tracer` stays empty | No span data has been published yet | Confirm the active client is emitting spans and remains connected |
| Runtime status shows an error banner | A runtime server or client protocol error occurred | Check the IDE logs, then restart the runtime server |

## Debug Tab Expectations

An empty or guidance-only `Debug` tab is not automatically a bug by itself.

Today the tab is expected to:

- show attach/setup guidance
- identify the active debug session when attached
- refresh best-effort Effect runtime snapshots while the session is paused and instrumentation is
  available
- report a clear message when the session is running or does not support expression evaluation

If `Context`, `Span Stack`, `Fibers`, or `Breakpoints` are empty, confirm the debug target was
started with the bundled instrumentation or use the toolbar refresh after pausing inside Effect code.

## Logs And Verification Status

Use the widget `Logs` action to open the IDE log directory when you need more detail.

The repository's current documented validation baseline is:

```bash
./gradlew build
./gradlew check
timeout 90s ./gradlew runIde
./gradlew verifyPlugin
```

The remaining explicit evidence gaps are:

- recorded manual editor smoke in WebStorm and IntelliJ IDEA Ultimate
- broader semantic smoke beyond the recorded real-binary LSP fixture checks

Those are follow-up validation items, not user-facing feature claims.
