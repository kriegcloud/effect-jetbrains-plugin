import { Context, Effect, Layer, Schema } from "effect"

export const schemaDefaultOff = Schema.decodeSync(Schema.String)("default-off")
// @effect-diagnostics-next-line schemaSync:warning
export const schemaOptIn = Schema.decodeSync(Schema.String)("opt-in")
export const schemaAfterOptIn = Schema.decodeSync(Schema.String)("default-off-again")

class Config extends Context.Service<Config>()("Config", {
  make: Effect.succeed({})
}) {
  static Default = Layer.effect(this, this.make)
}

export const visibleProvide = Effect.void.pipe(
  Effect.provide(Config.Default) // visible-strict
)

export const hiddenProvide = Effect.void.pipe(
  // @effect-diagnostics-next-line strictEffectProvide:off
  Effect.provide(Config.Default) // hidden-strict
)

Effect.succeed("visible-floating") // visible-floating

// @effect-diagnostics-next-line floatingEffect:off
Effect.succeed("hidden-floating-next-line") // hidden-floating-next-line

/** @effect-diagnostics floatingEffect:off */
Effect.succeed("hidden-floating-section") // hidden-floating-section

/** @effect-diagnostics floatingEffect:warning */
Effect.succeed("visible-floating-section") // visible-floating-section
