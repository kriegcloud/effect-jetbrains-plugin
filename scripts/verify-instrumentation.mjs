import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { cp, mkdtemp, readFile, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import { runInThisContext, runInNewContext } from "node:vm"

const scriptPath = fileURLToPath(import.meta.url)
const repoRoot = path.resolve(path.dirname(scriptPath), "..")
const fixtureRoot = path.join(repoRoot, "src/test/testData/fixtures/runtime/smoke-app")
const instrumentationPath = path.join(repoRoot, "src/main/resources/dev/effect/intellij/instrumentation/instrumentation.global.js")
const instrumentationKey = "effect/devtools/instrumentation"

function runCommand(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ["ignore", "pipe", "pipe"], timeout: 120_000 })
    let output = ""
    child.stdout.on("data", (chunk) => { output += chunk.toString() })
    child.stderr.on("data", (chunk) => { output += chunk.toString() })
    child.on("error", reject)
    child.on("close", (code, signal) => {
      if (code === 0) resolve(output.trim())
      else reject(new Error(`${command} ${args.join(" ")} exited ${code} (${signal ?? "no signal"})\n${output}`))
    })
  })
}

function assertLocation(location) {
  assert.ok(location, "Expected a resolved source location")
  assert.ok(location.path?.endsWith("index.mjs"), `Expected fixture source, got ${JSON.stringify(location)}`)
  assert.ok(Number.isInteger(location.line) && location.line >= 0)
  assert.ok(Number.isInteger(location.column) && location.column >= 0)
}

// Structural controls exercise the existing v3 option/global path and old-v4 fields,
// including runtimes carrying both shapes. Runtime assertions below use real npm packages.
function verifyLegacyFallbacks(source) {
  const sandbox = {}
  runInNewContext(source, sandbox, { filename: instrumentationPath })
  const api = sandbox[instrumentationKey]
  const frame = { name: "legacy.child", stack: () => "at work (index.mjs:12:3)" }
  const parent = { _tag: "Span", name: "legacy.parent", spanId: "parent" }
  const child = { _tag: "Span", name: "legacy.child", spanId: "child", parent: { _tag: "Some", value: parent } }
  const observers = []
  const legacy = { id: () => ({ id: 3 }), currentSpan: child, currentStackFrame: frame, addObserver: (observer) => observers.push(observer) }
  sandbox["effect/FiberCurrent"] = legacy
  assert.equal(api.getCurrentFiber(), legacy)
  assert.deepEqual(Array.from(api.getFiberCurrentSpanStackSnapshot(legacy, 64), (span) => span.name), ["legacy.child", "legacy.parent"])
  assertLocation(api.getFiberCurrentSpanStackSnapshot(legacy, 64)[0])
  const modern = { ...legacy, cache: { span: { ...child, name: "cache.child" }, stackFrame: { ...frame, name: "cache.child", stack: () => "at work (index.mjs:24:5)" } } }
  const cached = api.getFiberCurrentSpanStackSnapshot(modern, 64)[0]
  assert.equal(cached.name, "cache.child", "Cache span must take precedence over legacy fields")
  assert.equal(cached.line, 23, "Cache frame must take precedence over legacy fields")
  api.togglePauseOnDefects()
  observers[0]({ _tag: "Failure", cause: { _tag: "Die", defect: "legacy defect" } })
  assertLocation(api.getBreakpointSnapshot().location)
}

async function verifyRuntime(workspace) {
  const source = await readFile(instrumentationPath, "utf8")
  // EffectDebugBridgeService evaluates this file, then uses this global API.
  runInThisContext(source, { filename: instrumentationPath })
  const api = globalThis[instrumentationKey]
  assert.ok(api)
  const { Effect, Fiber } = await import(pathToFileURL(path.join(workspace, "node_modules/effect/dist/index.js")))
  const { version } = JSON.parse(await readFile(path.join(workspace, "node_modules/effect/package.json"), "utf8"))
  const { nestedSpans, defectInNestedSpan, longRunner } = await import(pathToFileURL(path.join(workspace, "index.mjs")))
  let snapshot
  let legacyFieldsAbsent
  await Effect.runPromise(nestedSpans(Effect.sync(() => {
    const fiber = api.getCurrentFiber()
    assert.ok(fiber, "IDE current-fiber global must be populated while running")
    legacyFieldsAbsent = !("currentSpan" in fiber) && !("currentStackFrame" in fiber)
    if (version === "4.0.0-rc.115") {
      assert.ok(legacyFieldsAbsent, "Negative control: rc.115 must lack both legacy fields")
      assert.ok(fiber.cache.span && fiber.cache.stackFrame)
    }
    snapshot = api.getFiberCurrentSpanStackSnapshot(fiber, 64)
    assert.deepEqual(snapshot.map((span) => span.name), ["smoke.work", "smoke.child", "smoke.parent"])
    assertLocation(snapshot.find((span) => span.path))
    assert.ok(api.getAliveFibersSnapshot().some((entry) => entry.isCurrent && entry.stack.length === 3))
    assert.ok(Array.isArray(api.getFiberCurrentContextSnapshot(fiber)))
  })))

  assert.equal(api.togglePauseOnDefects().pauseOnDefects, true)
  await Effect.runPromise(defectInNestedSpan())
  const pause = api.getBreakpointSnapshot()
  assert.equal(pause.pauseOnDefects, true)
  assert.equal(pause.values[0]?.label, "Fiber Defect")
  assertLocation(pause.location)
  assert.equal(api.getBreakpointSnapshot().location, null, "Pause state must be consumed")
  api.togglePauseOnDefects()

  const longFiber = Effect.runFork(longRunner)
  // Wait for the fork to suspend before requesting the same interrupt operation as the IDE.
  await new Promise((resolve) => setTimeout(resolve, 20))
  assert.ok(api.getAliveFibersSnapshot().some((fiber) => fiber.id === String(longFiber.id)))
  api.interruptFiber(String(longFiber.id))
  const exit = await Effect.runPromise(Fiber.await(longFiber))
  assert.equal(exit._tag, "Failure")
  assert.ok(exit.cause.reasons.some((reason) => reason._tag === "Interrupt"))
  assert.ok(!api.getAliveFibersSnapshot().some((fiber) => fiber.id === String(longFiber.id)))
  verifyLegacyFallbacks(source)
  console.log(`spans=${snapshot.map((span) => span.name).join(" > ")}; source=${snapshot.find((span) => span.path).path}; defect=${pause.location.path}:${pause.location.line + 1}; legacyFieldsAbsent=${legacyFieldsAbsent}; interrupt=PASS; legacy/cache controls=PASS`)
}

async function main() {
  const args = process.argv.slice(2)
  if (args[0] === "--worker" && args.length === 2) {
    await verifyRuntime(args[1])
    return
  }
  assert.ok(args.length === 0 || (args.length === 2 && args[0] === "--effect"), "Usage: node scripts/verify-instrumentation.mjs [--effect 4.0.0-rc.112,4.0.0-rc.115]")
  const versions = args.length ? args[1].split(",") : ["4.0.0-rc.112", "4.0.0-rc.115"]
  assert.ok(versions.every((version) => /^\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(version)), "Use exact Effect versions")
  const results = []
  for (const version of versions) {
    const workspace = await mkdtemp(path.join(tmpdir(), "effect-instrumentation-"))
    try {
      await cp(fixtureRoot, workspace, { recursive: true, filter: (source) => !["node_modules", "dist", "package-lock.json"].includes(path.basename(source)) })
      const manifest = JSON.parse(await readFile(path.join(workspace, "package.json"), "utf8"))
      manifest.dependencies.effect = version
      await writeFile(path.join(workspace, "package.json"), `${JSON.stringify(manifest, null, 2)}\n`)
      await runCommand("npm", ["install", "--ignore-scripts", "--no-fund", "--no-audit", "--package-lock=false"], workspace)
      const summary = await runCommand(process.execPath, [scriptPath, "--worker", workspace], workspace)
      results.push({ version, status: "PASS", summary, workspace })
    } catch (error) {
      results.push({ version, status: "FAIL", summary: error.stack || error.message, workspace })
    }
  }
  console.log("Effect version     Result  Evidence")
  for (const result of results) {
    console.log(`${result.version.padEnd(18)} ${result.status}    ${result.summary}\n  workspace: ${result.workspace}`)
  }
  if (results.some((result) => result.status === "FAIL")) process.exitCode = 1
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exitCode = 1
})
