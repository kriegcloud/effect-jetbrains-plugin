`tsgo-0.67.4.schema.json` is the unmodified `package/schema.json` from
`npm pack @effect/tsgo@0.67.4` (base package, not a platform binary package).
SHA-256: `1033935029dbc44cb55e22cf0159664f7bce5515d94ed668ea3f3e1d33238493`.
Upstream: https://www.npmjs.com/package/@effect/tsgo/v/0.67.4 (MIT).

This is a complete draft-07 tsconfig schema, including the discriminated
`@effect/language-service` plugin entry. The separate `oxlint-schema.json` was
inspected but is for Oxlint configuration and is not used here.

WebStorm 262 and 263 already register an embedded TSConfig schema. The public
JSON Schema ProviderFactory extension supplies this complete schema with
`userSchema` precedence, preserving ordinary compiler-option completion without
a second embedded-schema conflict. Each tsconfig.json/jsconfig.json gets its
own provider; the schema file is resolved from its nearest ancestor's
node_modules/@effect/tsgo/schema.json on every request, falling back to this copy.
Explicit `$schema` declarations remain under the IDE's normal schema-selection rules.

Settings/sync validation is advisory and checks the Effect option fragment,
including local references, objects, arrays, alternatives, enums, types and
numeric bounds. It never fetches remote schema references. Schema bytes load on
a pooled thread; validation reports a loading warning if the cache is not ready.
