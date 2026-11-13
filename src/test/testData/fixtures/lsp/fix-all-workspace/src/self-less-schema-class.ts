// @effect-diagnostics *:off
// @effect-diagnostics newSchemaClass:warning
import type { Cause } from "effect"
import { Schema } from "effect"

// A factory that fills `Self` with a structural type instead of taking the class as a type argument,
// the way `@alchemy.run/domain` declares `Rejection`, `Event` and `Command`. Published 0.67.4 through
// 0.69.0 report `new Refused()` although `Refused.make()` returns an `Instance<"Refused">`, which is
// not a `Refused`; the plugin drops that diagnostic (EffectNewSchemaClassGuard).
type Instance<Tag extends string> = { readonly _tag: Tag }

interface RejectionClass<Tag extends string>
  extends Schema.Class<Instance<Tag>, Schema.TaggedStruct<Tag, {}>, Cause.YieldableError> {}

declare const Rejection: <const Tag extends string>(tag: Tag) => RejectionClass<Tag>

export class Refused extends Rejection("Refused") {}

export const refusal: Refused = new Refused()
