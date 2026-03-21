import { Effect } from "effect"

export const broken = Effect.gen(function* () {
  const value = yield Effect.succeed(1)
  return value
})
