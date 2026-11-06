// @effect-diagnostics *:off
// @effect-diagnostics unnecessaryPipeChain:warning
import { Effect } from "effect"

const bump = Effect.map((n: number) => n + 1)
// The outer and inner rewrites overlap; the inner site remains for the next pull.
export const nested = Effect.succeed(0).pipe(bump).pipe(bump).pipe(bump)
