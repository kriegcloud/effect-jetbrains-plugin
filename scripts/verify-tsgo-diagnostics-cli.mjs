import assert from "node:assert/strict"
import { assertDiagnosticParity } from "./tsgo-diagnostic-parity.mjs"
import { spawn } from "node:child_process"
import { cp, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"

const fixturesRoot = fileURLToPath(new URL("../src/test/testData/fixtures/lsp/", import.meta.url))
const dependencies = ["@effect/tsgo@0.67.4", "typescript@7.0.2", "effect@4.1.2"]
// Baselines from verify-real-tsgo-lsp.mjs, before that lane applies any quick fixes.
// Additional findings are compared against a live LSP pull below.
const expected = {
  "healthy-workspace": {},
  "failing-workspace": { missingStarInYieldEffectGen: 1 },
  "fix-all-workspace": { missingStarInYieldEffectGen: 3, floatingEffect: 1, newSchemaClass: 1 },
  "diagnostic-directives-workspace": { strictEffectProvide: 1, floatingEffect: 2, schemaSync: 1 },
  "new-diagnostics-workspace": {
    floatingEffect: 1, obsoleteSchemaImport: 1, preferSucceedSomeOrNone: 2,
    mapConstToAs: 1, flatMapIdentityToFlatten: 1,
    allOfMapToForEach: 1, catchToOrElseSucceed: 1, flatMapToMap: 1, redundantOrDie: 1,
    newSchemaClass: 1, syncToSucceed: 1, schemaOpaqueInstanceMember: 1,
    preferTypedSchemaDecoder: 1, preferUnsafeConstructor: 1, promiseInEffectSuccess: 1,
    catchIfTagToCatchTag: 1, flatMapIgnoredParamToAndThen: 1, catchRefailToTapError: 1,
    effectSleepZeroToYieldNow: 1, flatMapToTap: 1, schemaClassObjectLiteral: 1,
    errorPayloadShadowsName: 1, preferEffectRecord: 1, apiStabilityLeak: 1,
  },
}

async function run(command, args, cwd, allowedCodes = [0]) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = ""
    let stderr = ""
    const timeout = setTimeout(() => child.kill("SIGKILL"), 180_000)
    child.stdout.on("data", (chunk) => { stdout += chunk })
    child.stderr.on("data", (chunk) => { stderr += chunk })
    child.on("error", (error) => { clearTimeout(timeout); reject(error) })
    child.on("close", (code, signal) => {
      clearTimeout(timeout)
      if (!allowedCodes.includes(code)) {
        reject(new Error(`${command} ${args.join(" ")} exited ${code} (${signal})\n${stdout}\n${stderr}`))
      } else {
        resolve({ code, stdout, stderr })
      }
    })
  })
}

// A small read-only LSP transport: the existing verifier is an executable script, not an
// importable helper. Use the same installed CLI/backend and tsconfig for both protocols.
async function lspFindings(binary, cwd, sources) {
  const child = spawn(binary, ["--lsp", "--stdio"], {
    cwd, stdio: ["pipe", "pipe", "pipe"],
  })
  let buffer = Buffer.alloc(0)
  let stderr = ""
  let nextId = 0
  let failure
  const pending = new Map()
  const closed = new Promise((resolve) => child.on("close", resolve))
  function fail(error) {
    failure = error
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timeout)
      waiter.reject(error)
    }
    pending.clear()
  }
  const send = (message) => {
    const body = Buffer.from(JSON.stringify(message))
    child.stdin.write(`Content-Length: ${body.length}\r\n\r\n`)
    child.stdin.write(body)
  }
  const notify = (method, params) => send({ jsonrpc: "2.0", method, params })
  const request = (method, params) => new Promise((resolve, reject) => {
    if (failure) { reject(failure); return }
    const id = ++nextId
    const timeout = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`LSP ${method} timed out\n${stderr}`))
    }, 30_000)
    pending.set(id, { resolve, reject, timeout })
    send({ jsonrpc: "2.0", id, method, params })
  })
  child.on("error", fail)
  child.stdin.on("error", fail)
  child.on("exit", (code, signal) => fail(new Error(`LSP exited ${code} (${signal})\n${stderr}`)))
  child.stderr.on("data", (chunk) => { stderr += chunk })
  child.stdout.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    try {
      while (true) {
        const headerEnd = buffer.indexOf("\r\n\r\n")
        if (headerEnd < 0) return
        const match = /Content-Length: (\d+)/i.exec(buffer.subarray(0, headerEnd).toString())
        assert(match, "Missing LSP Content-Length")
        const end = headerEnd + 4 + Number(match[1])
        if (buffer.length < end) return
        const message = JSON.parse(buffer.subarray(headerEnd + 4, end).toString())
        buffer = buffer.subarray(end)
        if (message.method && Object.hasOwn(message, "id")) {
          const result = message.method === "workspace/configuration"
            ? (message.params?.items ?? []).map(() => null) : null
          send({ jsonrpc: "2.0", id: message.id, result })
        } else if (pending.has(message.id)) {
          const waiter = pending.get(message.id)
          pending.delete(message.id)
          clearTimeout(waiter.timeout)
          if (message.error) waiter.reject(new Error(JSON.stringify(message.error)))
          else waiter.resolve(message.result)
        }
      }
    } catch (error) { fail(error) }
  })
  try {
    const rootUri = pathToFileURL(cwd).href
    const initialized = await request("initialize", {
      processId: process.pid, rootPath: cwd, rootUri,
      workspaceFolders: [{ uri: rootUri, name: path.basename(cwd) }],
      capabilities: { textDocument: { diagnostic: {} } },
    })
    assert(initialized.capabilities?.diagnosticProvider, "LSP must advertise pull diagnostics")
    notify("initialized", {})
    notify("workspace/didChangeConfiguration", { settings: {} })
    // Open and pull every fixture source, matching the CLI's project-wide check.
    const findings = []
    for (const [file, text] of sources) {
      const uri = pathToFileURL(path.join(cwd, "src", file)).href
      notify("textDocument/didOpen", {
        textDocument: { uri, languageId: "typescript", version: 1, text },
      })
      // The pull is ordered after didOpen, and returns a full report even for a healthy fixture.
      const report = await request("textDocument/diagnostic", { textDocument: { uri } })
      assert(Array.isArray(report?.items), `Expected full LSP diagnostic report for ${file}: ${JSON.stringify(report)}`)
      findings.push(...report.items.flatMap((diagnostic) => {
        const message = typeof diagnostic.message === "string" ? diagnostic.message : diagnostic.message?.value
        const name = message?.match(/effect\(([^)]+)\)\s*$/)?.[1]
        // The CLI deliberately excludes ordinary TypeScript diagnostics.
        // CLI lines are one-based; LSP ranges are zero-based.
        return name ? [{ file: `src/${file}`, name, line: diagnostic.range.start.line + 1,
          severity: { 1: "error", 2: "warning", 3: "message", 4: "message" }[diagnostic.severity] }] : []
      }))
    }
    await request("shutdown")
    notify("exit")
    return findings
  } finally {
    child.kill()
    const killTimeout = setTimeout(() => child.kill("SIGKILL"), 2_000)
    await closed
    clearTimeout(killTimeout)
  }
}

function verifyFindings(name, diagnostics, text) {
  for (const [rule, count] of Object.entries(expected[name])) {
    assert.equal(diagnostics.filter((item) => item.name === rule).length, count, `${name}: ${rule} count`)
  }
  if (name === "healthy-workspace") assert.deepEqual(diagnostics, [], "Healthy fixture must be clean")
  if (name !== "diagnostic-directives-workspace") return
  // CLI locations are one-based; use the fixture's markers so suppression checks survive edits.
  const checkLines = (rule, markers) => {
    const lines = markers.map((marker) => {
      const index = text.indexOf(marker)
      assert(index >= 0, `Missing fixture marker ${marker}`)
      return text.slice(0, index).split("\n").length
    })
    assert.deepEqual(diagnostics.filter((item) => item.name === rule).map((item) => item.line).sort((a, b) => a - b),
      lines.sort((a, b) => a - b), `${rule}: suppression/opt-in lines`)
  }
  checkLines("strictEffectProvide", ["// visible-strict"])
  checkLines("floatingEffect", ["// visible-floating\n", "// visible-floating-section"])
  checkLines("schemaSync", ["export const schemaOptIn"])
}

async function main() {
  const workspace = await mkdtemp(path.join(tmpdir(), "effect-tsgo-diagnostics-cli-"))
  try {
    // Like the LSP verifier, copy fixtures into a disposable workspace and install exact pins.
    // Share one parent node_modules across copies; never change the repository's dependencies.
    await writeFile(path.join(workspace, "package.json"), JSON.stringify({ private: true, type: "module" }))
    console.error(`Installing ${dependencies.join(", ")}`)
    await run("npm", ["install", "--prefix", workspace, "--no-fund", "--no-audit", "--no-package-lock",
      "--cache", path.join(workspace, "npm-cache"), ...dependencies], workspace)
    const cli = path.join(workspace, "node_modules/@effect/tsgo/dist/effect-tsgo.cjs")
    // The diagnostics command and this public resolver both select the backend matching
    // workspace TypeScript's gitHead. The JS CLI itself does not accept --lsp.
    const resolved = await run(process.execPath, ["--input-type=module", "--eval",
      "import getExePath from '@effect/tsgo/lib/getExePath'; console.log(getExePath())"], workspace)
    const binary = resolved.stdout.trim()
    assert(path.isAbsolute(binary), "Expected an absolute packaged native server path")
    const names = (await readdir(fixturesRoot)).sort()
    assert.deepEqual(names, Object.keys(expected).sort(), "Every LSP fixture needs CLI expectations")
    const results = {}
    for (const name of names) {
      console.error(`Verifying ${name}`)
      const cwd = path.join(workspace, name)
      await cp(path.join(fixturesRoot, name), cwd, { recursive: true })
      const text = await readFile(path.join(cwd, "src/index.ts"), "utf8")
      const args = [cli, "diagnostics", "--project", "tsconfig.json", "--format"]
      // Exit 1 is an expected diagnostic result, but only when the parsed summary proves it.
      const json = await run(process.execPath, [...args, "json"], cwd, [0, 1])
      const report = JSON.parse(json.stdout)
      assert(Array.isArray(report.diagnostics), `${name}: missing JSON diagnostics`)
      const fixtureSources = (await readdir(path.join(cwd, "src"))).filter((file) => /\.(?:ts|tsx|mts|cts)$/.test(file))
      assert.equal(report.summary.filesChecked, fixtureSources.length, `${name}: must check every fixture source (${fixtureSources.join(", ")})`)
      assert.equal(report.summary.filesWithoutPlugin, 0, `${name}: Effect plugin must be active`)
      for (const severity of ["error", "warning", "message"]) {
        const key = severity === "message" ? "messages" : `${severity}s`
        assert.equal(report.summary[key], report.diagnostics.filter((item) => item.severity === severity).length)
      }
      assert.equal(json.code, report.summary.errors > 0 ? 1 : 0, `${name}: diagnostics exit status`)
      verifyFindings(name, report.diagnostics, text)
      const cliNames = [...new Set(report.diagnostics.map((item) => item.name))].sort()
      const sources = await Promise.all(fixtureSources.map(async (file) => [file, await readFile(path.join(cwd, "src", file), "utf8")]))
      assertDiagnosticParity(report.diagnostics, await lspFindings(binary, cwd, sources), cwd, name)
      results[name] = { diagnostics: report.diagnostics.length, rules: cliNames, lspDiagnosticMultisetsAgree: true }
      if (name === "diagnostic-directives-workspace") {
        // The published spelling is github-actions, not github.
        const github = await run(process.execPath, [...args, "github-actions"], cwd, [0, 1])
        assert.equal(github.code, json.code)
        const annotations = github.stdout.split(/\r?\n/).filter((line) => /^::(?:error|warning|notice) /.test(line))
        assert.equal(annotations.length, report.diagnostics.length, "One GitHub annotation per finding")
        for (const diagnostic of report.diagnostics) {
          const kind = diagnostic.severity === "error" ? "error" : diagnostic.severity === "warning" ? "warning" : "notice"
          assert(annotations.some((line) => line.startsWith(`::${kind} file=${diagnostic.file},line=${diagnostic.line},`)
            && line.includes(`,title=${diagnostic.name}::`)), `Missing GitHub annotation for ${diagnostic.name}`)
        }
        results[name].githubAnnotations = annotations.length
      }
    }
    console.log(JSON.stringify({ dependencies, fixtures: results }, null, 2))
  } finally {
    await rm(workspace, { recursive: true, force: true })
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
