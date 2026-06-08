import { Effect, Schema } from "effect"

declare const first: Effect.Effect<number, unknown>
declare const second: Effect.Effect<string, unknown>

export const recover = Effect.fail("error").pipe(
  Effect.catch(() => Effect.succeed(42))
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
