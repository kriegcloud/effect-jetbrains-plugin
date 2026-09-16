# Effect plugin 0.1.7 smoke (about 15 minutes)

Record the IDE build, plugin version, OS, Node version, and each step's actual result. Automated
instrumentation and sandbox boots do not replace this human paused-session/editor check.
Use WebStorm 2026.3 EAP build **263.4732.34**, Node **22+** (global WebSocket required), and this
fixture with exact **effect 4.0.0-rc.115** / **typescript 7.0.2**. Keep edits to quick-fix examples
temporary so the original examples remain available for the next pass.

1. **Install and enable (1 min).** With the user's IDE closed, install the prepared 0.1.7 ZIP
   through the normal local-plugin flow. Restart WebStorm and confirm Settings → Plugins lists
   **Effect TSGO 0.1.7**, enabled. Open this `smoke-app` directory as a project.
2. **Dependencies and LSP (2 min).** Run `npm install --no-package-lock` in this directory. Enable
   Effect integration in the project's Effect settings and choose the published **@effect/tsgo
   0.45.0** binary (managed pinned 0.45.0 or the verified manual binary). Open `example.ts` and
   confirm the Effect status widget reports a running server. The `@effect/language-service`
   entry in tsconfig configures the service embedded in tsgo; no separate package is required.
3. **New diagnostics and fixes (2 min).** Expect `obsoleteSchemaImport` on the old schema import,
   at warning severity, with suppression actions but no migration rewrite. Expect
   `preferSucceedSomeOrNone` on `some` and `none`; invoke the quick fixes and verify
   `Effect.succeedSome(42)` and `Effect.succeedNone`. Expect `allOfMapToForEach` on `mapped`;
   apply its fix and verify `Effect.forEach` replaces `Effect.all` plus Array.map. Undo these edits.
4. **Opt-in rule and completion (1 min).** Expect `schemaSync` on `decoded` because the preceding
   `@effect-diagnostics-next-line schemaSync:warning` enables it. Temporarily remove that directive:
   this warning should disappear (the rule defaults off). Undo. In a directive comment, check
   completion offers `obsoleteSchemaImport`, `preferSucceedSomeOrNone`, `allOfMapToForEach`, and
   `schemaSync`; after `schemaSync:` expect severity completion.
5. **Layer hover (1 min).** Hover `appLayer` and follow **Show full graph**. Expect a readable
   Mermaid graph connecting Client to Config. Record whether the IDE renders/opens it correctly.
6. **DevTools connection and tracing (2 min).** Start the plugin's DevTools server on port **34437**
   before starting `npm start`. Expect an rc.115 client connection, repeated nested spans
   `smoke.parent → smoke.child → smoke.work`, and an expected failure every fourth tick. The
   parent catches that typed failure and the next tick continues. Inspect span timing and details.
7. **Metrics (1 min).** Wait at least one metrics polling interval (up to 10 seconds). Expect
   `smoke_ticks` (counter) to increase, `smoke_balance` (gauge) to alternate through negative and
   positive values, and `smoke_latency_ms` (histogram) to accumulate samples in explicit buckets.
8. **Paused debugger and source (2 min).** Stop the terminal run and create a Node.js debug
   configuration for `index.mjs`. Start Debug; attach the Effect debugger through the plugin.
   Set a breakpoint on the marked `yield* body` line in `work`. On pause, refresh the Effect
   snapshot: expect `smoke.work`, `smoke.child`, `smoke.parent` in inner-to-outer order, a current
   fiber, context, and clickable source locations in this fixture. Follow one location and verify
   it opens the correct source. Record an actual paused-session result.
9. **Defect observer (1 min).** Disable the ordinary breakpoint, enable **Pause on Defects**, and
   resume. Within about 15 seconds expect the intentional child defect, a **Fiber Defect** value,
   and a non-null source reveal. This is a best-effort pause on fiber completion, not a guarantee
   of a pause at the original throw site. Resume; the defect loop should continue.
10. **Interrupt and cleanup (2 min).** While paused again inside a tick, select the fiber whose
    span is `smoke.long-runner` (its ID is also logged at startup), then use the plugin's interrupt
    action. Resume and capture another snapshot: that fiber should disappear while ticks continue.
    Stop the debug run, stop DevTools, and undo any example edits. Record failures and observations;
    the docs lane owns any parity-matrix update after this pass.
