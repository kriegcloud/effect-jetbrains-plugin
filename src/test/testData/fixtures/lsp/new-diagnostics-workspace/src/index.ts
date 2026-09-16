import { Effect, Option, Schema, Scope } from "effect"

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
