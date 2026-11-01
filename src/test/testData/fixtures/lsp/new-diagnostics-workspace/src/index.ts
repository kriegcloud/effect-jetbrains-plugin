import { Data, Effect, Option, Schema, Scope } from "effect"

// @ts-expect-error obsolete package intentionally absent, as in the upstream v4 fixture
import * as OldSchema from "@effect/schema/Schema"
export { OldSchema }

export const some = Effect.succeed(Option.some(42))
export const none = Effect.succeed(Option.none())
export const mapped = Effect.all([1, 2, 3].map((n) => Effect.succeed(n + 1)))

declare const first: Effect.Effect<number, unknown>
declare const second: Effect.Effect<string, unknown>

export const recover = Effect.fail("error").pipe(
  Effect.catch(() => Effect.succeed(42))
)

export const increment = Effect.succeed(1).pipe(
  Effect.flatMap((value) => Effect.succeed(value + 1))
)

export const dieLater = Effect.gen(function*() {
  const one = yield* first.pipe(Effect.orDie)
  const two = yield* second.pipe(Effect.orDie)

  return [one, two] as const
})

export const User = Schema.Struct({
  age: Schema.Number,
  score: Schema.NumberFromString
})

export class Point extends Schema.Class<Point>("Point")({
  x: Schema.Number,
  y: Schema.Number
}) {}

export const origin = new Point({ x: 0, y: 0 })

export const answer = Effect.sync(() => 42)

export class Label extends Schema.Opaque<Label>()(Schema.Struct({
  text: Schema.String
})) {
  render(): string {
    return "label"
  }
}

export const scope = Effect.runSync(Scope.make())

export const eventualNumber = Effect.succeed(Promise.resolve(1))

export const decodedUser = Schema.decodeUnknownSync(User)({ age: 1, score: "10" })

export const floatingInGen = Effect.gen(function*() {
  first

  return yield* second
})

class NotFound extends Data.TaggedError("NotFound")<{}> {}
class Timeout extends Data.TaggedError("Timeout")<{}> {}

declare const lookup: Effect.Effect<string, NotFound | Timeout>

export const recovered = lookup.pipe(
  Effect.catchIf(
    (error) => error._tag === "NotFound",
    () => Effect.succeed("guest")
  )
)

const constantNext = Effect.succeed("next")

export const sequenced = Effect.succeed(1).pipe(Effect.flatMap(() => constantNext))

declare const save: Effect.Effect<void, Error>
declare const rollback: Effect.Effect<void>

export const rolledBack = save.pipe(
  Effect.catch(error => rollback.pipe(Effect.andThen(Effect.fail(error))))
)

/** @stability unstable */
export const unstablePreviewApi = () => 1

/** @stability experimental */
export const experimentalPreviewApi = () => 1

export const stabilityUses = [unstablePreviewApi(), experimentalPreviewApi()]

// 0.52.0 style suggestions with quick fixes.
export const cooperativeYield = Effect.sleep(0)

declare const audit: (value: number) => Effect.Effect<void>

export const observed = Effect.succeed(1).pipe(
  Effect.flatMap((value) => audit(value).pipe(Effect.as(value)))
)

// 0.52.0 schemaClassMissingBrand fires on `Point` above; a branded class is the control, and the
// 0.54.0 schemaClassObjectLiteral warning fires on the spread copy.
export class BrandedPoint extends Schema.Class<BrandedPoint, { readonly brand: unique symbol }>("BrandedPoint")({
  x: Schema.Number
}) {}

export const brandedOrigin = BrandedPoint.make({ x: 0 })

export const copiedPoint: BrandedPoint = { ...brandedOrigin }

// 0.53.0 errorPayloadShadowsName.
class ToolNotFound extends Data.TaggedError("ToolNotFound")<{ readonly name: string }> {}

export const toolError = new ToolNotFound({ name: "search" })

// Opt-in rules enabled through tsconfig diagnosticSeverity: preferEffectArray and preferEffectRecord
// (0.52.0, effect-native preset), preferSchemaTaggedError (0.52.0, also fires on the
// Data.TaggedError classes above), and apiStabilityLeak (0.50.0, maintainers preset).
const numbers = [1, 2, 3]

export const firstLarge = numbers.find((n) => n > 1)

export const scoreEntries = Object.entries({ alice: 1 })

/** @stability experimental */
export interface ExperimentalShape {
  readonly value: string
}

export interface StableShape {
  readonly leaked: ExperimentalShape
}
