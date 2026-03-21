import { Effect, Layer, ServiceMap } from "effect"

class Database extends ServiceMap.Service<Database>()("Database", {
  make: Effect.succeed({
    query: (_sql: string) => Effect.succeed(["row"] as const),
  }),
}) {
  static Default = Layer.effect(this, this.make)
}

class Cache extends ServiceMap.Service<Cache>()("Cache", {
  make: Effect.as(Database.asEffect(), {
    get: (_key: string) => Effect.succeed("cached"),
  }),
}) {
  static Default = Layer.effect(this, this.make)
}

export const appLayer = Cache.Default.pipe(Layer.provide(Database.Default))

export function standardShouldAppear() {
  return 42
}

export const sample = Effect.gen(function* () {
  const db = yield* Database
  const rows = yield* db.query("select 1")
  return rows.length
})
