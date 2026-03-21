# Design: UI and UX

## Primary surfaces

### Project settings page
Create one project-level configurable at `Settings | Tools | Effect`.

Sections:
- `Binary`
  - version mode: latest, pinned, manual
  - pinned version
  - manual binary path
  - extra environment variables
- `Language Server`
  - initialization options JSON
  - workspace configuration JSON
  - restart-on-apply notice
- `Dev Tools`
  - runtime server port
  - metrics poll interval
  - span-stack ignore list
- `Debugger`
  - inject `NODE_OPTIONS`
  - eligible debug configuration types

Tracer updates are push-driven from runtime span events, so there is no separate tracer poll setting.

Validation rules:
- pinned mode requires a version
- manual mode requires an executable file
- JSON fields must parse before apply
- the Dev Tools port and metrics poll interval must remain within valid ranges

### Status widget
Use the JetBrains LSP widget entry created from the Effect LSP descriptor. Do not add a second custom status-bar widget.

States:
- `Not Configured`
- `Resolving Binary`
- `Starting`
- `Running`
- `Restart Required`
- `Error`

Widget actions:
- open Effect settings
- restart the language server
- open the plugin log/output location
- focus `Effect Dev Tools`

The widget reports only language-server status. Dev Tools runtime status is intentionally kept inside the tool window.

### Tool window
Create one tool window named `Effect Dev Tools`.

Shared toolbar actions:
- start/stop runtime server
- restart runtime server
- open settings
- select active client
- reset metrics
- reset tracer
- attach active debug session once the debug bridge milestone ships

Guaranteed tabs:
- `Clients`
  - server summary banner
  - connected-client list
  - selected client details
- `Metrics`
  - tree or table summary
  - selected metric detail pane
- `Tracer`
  - span tree
  - selected span detail pane
  - source reveal action when location data exists

Later tab group:
- `Debug`
  - secondary tabs or segmented content for `Context`, `Span Stack`, `Fibers`, `Breakpoints`
  - only enabled after the debugger bridge exists

## UX decisions

### First run
- Do not auto-start the Dev Tools runtime server.
- Offer binary resolution when the first supported file needs LSP startup.
- Keep the initial tool window state useful even with zero clients connected.

### Error handling
- Validation failures stay inline on the settings form.
- Startup and runtime failures raise actionable notifications.
- Detailed process/debug transport errors are routed to IDE logs or a dedicated output action, not stuffed into the status widget text.

### Debugger bridge
- The tool window is the primary home for debugger-bound Effect data.
- Later debugger affordances may add actions in debugger UI, but they should focus or filter the tool window rather than duplicating whole trees in XDebugger panes.
- If a debug session is present but instrumentation is missing, show a structured empty state with setup guidance and attach status.

## Fallback behavior for known parity gaps

| Gap | User-facing behavior |
| --- | --- |
| JCEF unavailable or disabled | Keep the `Tracer` tab in Swing with tree + details; advanced tracer action is hidden or disabled with an explanation |
| Advanced tracer UI not implemented yet | The `Tracer` tab remains the default and only tracer surface |
| Debug bridge not implemented yet | `Effect Dev Tools` still ships with `Clients`, `Metrics`, and `Tracer`; no fake debug tab is shown |
| Debug bridge implemented but session lacks instrumentation | `Debug` shows an empty state with setup guidance and attach status, not stale or fabricated data |
| VS Code local layer-mermaid preview command | No matching button or command is exposed until a real custom transport exists; hover links remain the supported layer-graph path |
| VS Code debug-sidebar placement | Any debugger action should focus the `Effect Dev Tools` tool window instead of trying to mimic VS Code layout |
