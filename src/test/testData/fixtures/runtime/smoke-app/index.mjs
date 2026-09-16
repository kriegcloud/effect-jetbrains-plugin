import { Effect, Fiber, Metric } from "effect"
import { DevTools } from "effect/unstable/devtools"
import { pathToFileURL } from "node:url"

// Shared with scripts/verify-instrumentation.mjs; importing does not start the app.
const work = Effect.fn("smoke.work")(function*(body) {
  const breakpointMarker = "BREAKPOINT: inside smoke.work / smoke.child / smoke.parent"
  yield* body // Set an IDE breakpoint here; inspect breakpointMarker and the Effect span stack.
  return breakpointMarker
})

export const nestedSpans = (body) => Effect.gen(function*() {
  return yield* work(body).pipe(Effect.withSpan("smoke.child"))
}).pipe(Effect.withSpan("smoke.parent"))

// The failing fiber inherits the nested span/frame context. Its completion triggers the
// instrumentation's best-effort defect observer, even when the parent inspects its Exit.
export const defectInNestedSpan = () => nestedSpans(Effect.gen(function*() {
  const child = yield* Effect.forkChild(Effect.die(new Error("smoke: intentional 15-second defect")))
  return yield* Fiber.await(child)
}))

export const longRunner = Effect.never.pipe(Effect.withSpan("smoke.long-runner"))

const ticks = Metric.counter("smoke_ticks")
const balance = Metric.gauge("smoke_balance")
const latency = Metric.histogram("smoke_latency_ms", { boundaries: [10, 25, 50, 100, 250, 500] })

export const app = Effect.gen(function*() {
  const longFiber = yield* Effect.forkChild(longRunner)
  yield* Effect.log(`Smoke app running; interrupt long-runner fiber ${longFiber.id} from the Effect debugger`)
  yield* Effect.forkChild(Effect.gen(function*() {
    yield* Effect.sleep("15 seconds")
    yield* defectInNestedSpan()
  }).pipe(Effect.forever))

  let tick = 0
  yield* Effect.gen(function*() {
    tick += 1
    yield* nestedSpans(Effect.gen(function*() {
      yield* Metric.update(ticks, 1)
      yield* Metric.update(balance, (tick % 7) - 3)
      yield* Metric.update(latency, 10 + (tick % 5) * 40)
      yield* Effect.sleep("100 millis")
      if (tick % 4 === 0) {
        return yield* Effect.fail("smoke: expected child failure")
      }
    })).pipe(Effect.catch((error) => Effect.log(error)))
    yield* Effect.sleep("1 second")
  }).pipe(Effect.forever)
})

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  // Node 22+ provides the global WebSocket used by DevTools.layer(). The IDE is the server.
  await Effect.runPromise(app.pipe(Effect.provide(DevTools.layer()))) // ws://localhost:34437
}
