// @effect-diagnostics *:off
// @effect-diagnostics catchAllToMapError:warning
import { Effect } from "effect"

// Published 0.67.4's function-expression rewrite leaves an invalid mapError callback.
export const harmful = Effect.fail("error").pipe(
  Effect.catch(function(cause) {
    return Effect.fail(new Error(cause))
  })
)
