// @effect-diagnostics *:off
// @effect-diagnostics yieldInCallArgument:warning
import { Effect } from "effect"

declare const load: Effect.Effect<number>
declare function consume(value: number): void
// Independently computed fixes both hoist `loaded` into the same scope.
export const collision = Effect.gen(function* () {
  consume(yield* load)
  consume(yield* load)
})
