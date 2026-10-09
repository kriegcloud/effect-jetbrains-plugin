import { Effect } from "effect"

declare const sideEffect: Effect.Effect<void>

export const first = Effect.gen(function* () {
  const value = yield Effect.succeed(1)
  return value
})

export const second = Effect.gen(function* () {
  const value = yield Effect.succeed(2)
  return value
})

export const third = Effect.gen(function* () {
  const value = yield Effect.succeed(3)
  return value
})

export const floating = Effect.gen(function* () {
  sideEffect

  return yield* Effect.succeed(4)
})
