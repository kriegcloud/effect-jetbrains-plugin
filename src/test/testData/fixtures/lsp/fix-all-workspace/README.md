The `--only fix-all` lane in `scripts/verify-real-tsgo-lsp.mjs` runs `index.ts`
(the independent-site smoke) and these native safety regressions with the published
`@effect/tsgo@0.67.4` binary and `effect@4.1.2`:

| Fixture | Rule | Required result |
| --- | --- | --- |
| `src/nested.ts` | `unnecessaryPipeChain` | Two overlapping rewrites converge over at least two validated passes. |
| `src/collision.ts` | `yieldInCallArgument` | The merged fixes declare the same generated name; roll back, recompute individually, and converge without errors. |
| `src/harmful.ts` | `catchAllToMapError` | The standalone function-expression rewrite introduces errors; retry alone, reject, and restore the original text and rule diagnostic. |

The three files minimize patterns from the pinned upstream `effect-v4` fixtures
`unnecessaryPipeChain.ts`, `yieldInCallArgument.ts`, and `catchAllToMapError.ts`.
Each starts without error-severity diagnostics. The verifier reports pass counts,
validation pulls, rollbacks, rejected error signatures, and remaining diagnostics.
It compares errors as a multiset of code and message, ignoring shifted positions.

This is a native LSP protocol regression, implemented in the JavaScript verifier.
It does not execute Kotlin's `runFixAllPasses`; production orchestration remains
covered separately by `EffectFixAllPassesTest`.
