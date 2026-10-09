import { Context, Data, Effect, Layer, Option, Schema } from "effect"

// Intentional migration diagnostic. No @effect/schema dependency is needed or installed.
// @ts-expect-error obsolete package intentionally absent
import * as OldSchema from "@effect/schema/Schema"
export { OldSchema }

// Quick fixes: Effect.succeedSome(42), Effect.succeedNone, and Effect.forEach(...).
export const some = Effect.succeed(Option.some(42))
export const none = Effect.succeed(Option.none())
export const mapped = Effect.all([1, 2, 3].map((n) => Effect.succeed(n + 1)))

// schemaSync is off by default. This directive opts in on precisely the following line.
// @effect-diagnostics-next-line schemaSync:warning
export const decoded = Schema.decodeSync(Schema.String)("smoke")

// Quick fixes: Effect.catchTag("NotFound", ...) and Effect.andThen(constantNext).
class NotFound extends Data.TaggedError("NotFound")<{}> {}
class Timeout extends Data.TaggedError("Timeout")<{}> {}
declare const lookup: Effect.Effect<string, NotFound | Timeout>
export const recovered = lookup.pipe(
  Effect.catchIf((error) => error._tag === "NotFound", () => Effect.succeed("guest"))
)
const constantNext = Effect.succeed("next")
export const sequenced = Effect.succeed(1).pipe(Effect.flatMap(() => constantNext))

// unstableApiUsage warns on uses of APIs tagged @stability unstable.
/** @stability unstable */
export const unstablePreviewApi = () => 1
export const unstableUse = unstablePreviewApi()

class Config extends Context.Service<Config, { readonly endpoint: string }>()("smoke/Config") {}
class Client extends Context.Service<Client, { readonly endpoint: string }>()("smoke/Client") {}

const configLayer = Layer.succeed(Config, { endpoint: "ws://localhost:34437" })
const clientLayer = Layer.effect(Client, Effect.gen(function*() {
  const config = yield* Config
  return { endpoint: config.endpoint }
}))

// Hover appLayer and follow "Show full graph" to inspect the Mermaid Layer graph.
export const appLayer = clientLayer.pipe(Layer.provide(configLayer))
