# Troubleshooting

## Start With The Current Surface

When something goes wrong, check these in order:

1. The Effect LSP widget state
2. `Settings | Tools | Effect`
3. The `Effect Dev Tools` status banner and active client selection
4. The IDE log directory from the widget's `Logs` action

## Common LSP Issues

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Widget stays on `Resolving Binary` or moves to `Error` in `LATEST` or `PINNED` mode | The plugin cannot reach npm, the version is wrong, or your platform is unsupported by the published package | Recheck connectivity and the configured version, then restart the server |
| Manual mode will not apply | The path is blank, invalid, missing, not a file, or not executable | Provide a valid executable native `tsgo` path |
| Widget reaches `Restart Required` | LSP-relevant settings changed | Use the widget restart action after applying settings |
| No LSP startup when a file opens | The file is not a supported TypeScript extension or the server failed before startup completed | Use a supported file and inspect the widget state plus logs |

## Binary Mode Checks

### `LATEST`

- Requires network access to npm during version resolution and download
- Chooses the current `latest` dist-tag for `@effect/tsgo`

### `PINNED`

- Requires a non-blank version string
- Uses the exact version you configured

### `MANUAL`

- Requires an executable native `tsgo` path
- Does not rely on the plugin-managed download/cache path

## Managed Cache Issues

Managed binaries are stored under the JetBrains system cache area in an `effect-tsgo` directory.

If a managed install looks stale or corrupted:

1. Close the IDE or stop using the project
2. Remove the `effect-tsgo` cache directory from the IDE system path
3. Reopen the project and let the plugin resolve the binary again

## Runtime Dev Tools Issues

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Runtime server will not start | The configured port is unavailable or startup failed | Change the Dev Tools port in settings, then retry |
| `Clients` stays empty | No runtime client is connected to the local server yet | Start the runtime server and confirm the client is using the configured port |
| `Metrics` says no active client | No client is selected or connected | Use the toolbar client selector or connect a runtime client |
| `Tracer` stays empty | No span data has been published yet | Confirm the active client is emitting spans and remains connected |
| Runtime status shows an error banner | A runtime server or client protocol error occurred | Check the IDE logs, then restart the runtime server |

## Debug Tab Expectations

An empty or guidance-only `Debug` tab is not currently a bug by itself.

Today the tab is expected to:

- show attach/setup guidance
- identify the active debug session when attached
- avoid fabricating live Effect runtime snapshots

Live `Context`, `Span Stack`, `Fibers`, and `Breakpoints` data remain deferred.

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
- richer real-binary semantic smoke evidence against a native `@effect/tsgo` executable in this
  environment

Those are follow-up validation items, not user-facing feature claims.
