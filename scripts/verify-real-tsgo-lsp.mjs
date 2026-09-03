import { chmod, mkdtemp, readFile, writeFile, cp } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { pathToFileURL } from "node:url"
import { spawn } from "node:child_process"

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..")
const fixturesRoot = path.join(repoRoot, "src", "test", "testData", "fixtures", "lsp")
const REQUEST_TIMEOUT_MS = 60_000

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function parseArgs(argv) {
  const args = {}
  for (let index = 0; index < argv.length; index += 1) {
    const current = argv[index]
    if (current === "--binary") {
      args.binary = argv[index + 1]
      index += 1
    } else if (current === "--only") {
      args.only = argv[index + 1]
      index += 1
    }
  }
  return args
}

const { binary, only = "all" } = parseArgs(process.argv.slice(2))
if (!binary) {
  console.error("Usage: node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/tsgo [--only healthy|failing|new-diagnostics|diagnostic-directives|all]")
  process.exit(1)
}

class LspClient {
  constructor(command, args, cwd) {
    this.nextId = 1
    this.pending = new Map()
    this.notificationWaiters = []
    this.stdoutBuffer = Buffer.alloc(0)
    this.stderr = ""
    this.process = spawn(command, args, {
      cwd,
      stdio: ["pipe", "pipe", "pipe"],
    })
    this.process.stdout.on("data", (chunk) => this.handleStdout(chunk))
    this.process.stderr.on("data", (chunk) => {
      this.stderr += chunk.toString("utf8")
    })
    this.process.on("error", (error) => {
      for (const { reject, timeout } of this.pending.values()) {
        clearTimeout(timeout)
        reject(error)
      }
      this.pending.clear()
      for (const waiter of this.notificationWaiters.splice(0)) {
        waiter.reject(error)
      }
    })
    this.process.on("exit", (code, signal) => {
      const error = new Error(`LSP process exited early (code=${code}, signal=${signal})\n${this.stderr}`)
      for (const { reject, timeout } of this.pending.values()) {
        clearTimeout(timeout)
        reject(error)
      }
      this.pending.clear()
      for (const waiter of this.notificationWaiters.splice(0)) {
        waiter.reject(error)
      }
    })
  }

  send(message) {
    const payload = Buffer.from(JSON.stringify(message), "utf8")
    this.process.stdin.write(`Content-Length: ${payload.length}\r\n\r\n`)
    this.process.stdin.write(payload)
  }

  request(method, params, timeoutMs = REQUEST_TIMEOUT_MS) {
    const id = this.nextId++
    const promise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(`Timed out waiting for ${method}\n${this.stderr}`))
        }
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timeout })
    })
    this.send({
      jsonrpc: "2.0",
      id,
      method,
      params,
    })
    return promise
  }

  notify(method, params) {
    this.send({
      jsonrpc: "2.0",
      method,
      params,
    })
  }

  waitForNotification(method, predicate, timeoutMs = REQUEST_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
      const waiter = {
        method,
        predicate,
        resolve,
        reject,
        timeout: setTimeout(() => {
          this.notificationWaiters = this.notificationWaiters.filter((candidate) => candidate !== waiter)
          reject(new Error(`Timed out waiting for ${method}`))
        }, timeoutMs),
      }
      this.notificationWaiters.push(waiter)
    })
  }

  async shutdown() {
    try {
      await this.request("shutdown", null)
    } catch {
      // ignore shutdown races during cleanup
    }
    this.notify("exit", null)
    this.process.kill()
  }

  handleStdout(chunk) {
    this.stdoutBuffer = Buffer.concat([this.stdoutBuffer, chunk])
    while (true) {
      const headerEnd = this.stdoutBuffer.indexOf("\r\n\r\n")
      if (headerEnd === -1) {
        return
      }
      const header = this.stdoutBuffer.slice(0, headerEnd).toString("utf8")
      const contentLengthLine = header
        .split("\r\n")
        .find((line) => line.toLowerCase().startsWith("content-length:"))
      if (!contentLengthLine) {
        throw new Error(`Missing Content-Length header in:\n${header}`)
      }
      const contentLength = Number(contentLengthLine.split(":")[1].trim())
      const messageEnd = headerEnd + 4 + contentLength
      if (this.stdoutBuffer.length < messageEnd) {
        return
      }
      const body = this.stdoutBuffer.slice(headerEnd + 4, messageEnd).toString("utf8")
      this.stdoutBuffer = this.stdoutBuffer.slice(messageEnd)
      this.handleMessage(JSON.parse(body))
    }
  }

  handleMessage(message) {
    if (typeof message.id !== "undefined" && typeof message.method === "string") {
      this.handleServerRequest(message)
      return
    }

    if (typeof message.id !== "undefined") {
      const pending = this.pending.get(message.id)
      if (!pending) {
        return
      }
      this.pending.delete(message.id)
      clearTimeout(pending.timeout)
      if (Object.hasOwn(message, "error")) {
        pending.reject(new Error(JSON.stringify(message.error)))
      } else {
        pending.resolve(message.result)
      }
      return
    }

    for (const waiter of [...this.notificationWaiters]) {
      if (waiter.method !== message.method) {
        continue
      }
      if (!waiter.predicate(message.params)) {
        continue
      }
      clearTimeout(waiter.timeout)
      this.notificationWaiters = this.notificationWaiters.filter((candidate) => candidate !== waiter)
      waiter.resolve(message.params)
    }
  }

  handleServerRequest(message) {
    let result = null
    if (message.method === "workspace/configuration") {
      const items = Array.isArray(message.params?.items) ? message.params.items : []
      result = items.map(() => null)
    }
    this.send({
      jsonrpc: "2.0",
      id: message.id,
      result,
    })
  }
}

async function requestWithRetries(client, method, params, options = {}) {
  const {
    attempts = 3,
    timeoutMs = 20_000,
    delayMs = 5_000,
    responsePredicate = () => true,
  } = options

  let lastError
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const result = await client.request(method, params, timeoutMs)
      if (responsePredicate(result)) {
        return result
      }
      lastError = new Error(`${method} returned an incomplete result on attempt ${attempt}`)
    } catch (error) {
      lastError = error
    }

    if (attempt < attempts) {
      console.error(`${method} attempt ${attempt} did not complete, retrying...`)
      await sleep(delayMs)
    }
  }

  throw lastError
}

async function copyFixtureWorkspace(name) {
  const source = path.join(fixturesRoot, name)
  const destination = await mkdtemp(path.join(tmpdir(), `effect-tsgo-${name}-`))
  await cp(source, destination, { recursive: true })
  return destination
}

async function runCommand(command, args, cwd) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: ["ignore", "pipe", "pipe"],
    })
    child.stdout.pipe(process.stderr)
    child.stderr.pipe(process.stderr)
    child.on("error", reject)
    child.on("exit", (code) => {
      if (code === 0) {
        resolve()
      } else {
        reject(new Error(`${command} ${args.join(" ")} failed with exit code ${code}`))
      }
    })
  })
}

function positionAt(text, needle, offset = 0) {
  const index = text.indexOf(needle)
  if (index === -1) {
    throw new Error(`Could not find "${needle}"`)
  }
  const absolute = index + offset
  const before = text.slice(0, absolute)
  const line = before.split("\n").length - 1
  const lastNewline = before.lastIndexOf("\n")
  const character = absolute - (lastNewline + 1)
  return { line, character }
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function hoverText(hover) {
  if (!hover || !hover.contents) {
    return ""
  }
  const { contents } = hover
  if (typeof contents === "string") {
    return contents
  }
  if (Array.isArray(contents)) {
    return contents.map(hoverText).join("\n")
  }
  if (typeof contents.value === "string") {
    return contents.value
  }
  if (typeof contents.language === "string" && typeof contents.value === "string") {
    return contents.value
  }
  return JSON.stringify(contents)
}

function fullDocumentRange(text) {
  const lines = text.split("\n")
  return {
    start: { line: 0, character: 0 },
    end: {
      line: lines.length - 1,
      character: lines[lines.length - 1].length,
    },
  }
}

function diagnosticMessages(diagnostics) {
  return diagnostics.map((diagnostic) => String(diagnostic.message ?? ""))
}

function diagnosticsForRule(diagnostics, ruleName) {
  return diagnostics.filter((diagnostic) => String(diagnostic.message ?? "").includes(`effect(${ruleName})`))
}

function hasDiagnosticOnLine(diagnostics, line) {
  return diagnostics.some((diagnostic) => diagnostic?.range?.start?.line === line)
}

function lineNumberOf(text, needle) {
  const index = text.indexOf(needle)
  assert(index >= 0, `Expected source to contain ${needle}`)
  return text.slice(0, index).split("\n").length - 1
}

function diagnosticsFromReport(report) {
  if (Array.isArray(report)) {
    return report
  }
  if (Array.isArray(report?.items)) {
    return report.items
  }
  if (report?.relatedDocuments && typeof report.relatedDocuments === "object") {
    return Object.values(report.relatedDocuments).flatMap(diagnosticsFromReport)
  }
  return []
}

async function pullDiagnosticsWithRetries(client, uri, predicate) {
  const report = await requestWithRetries(client, "textDocument/diagnostic", {
    textDocument: { uri },
  }, {
    attempts: 6,
    timeoutMs: 15_000,
    delayMs: 2_500,
    responsePredicate: (candidate) => {
      const diagnostics = diagnosticsFromReport(candidate)
      return Array.isArray(diagnostics) && diagnostics.length > 0 && predicate(diagnostics)
    },
  })
  return diagnosticsFromReport(report)
}

async function verifyHealthyWorkspace(workspacePath) {
  const indexPath = path.join(workspacePath, "src", "index.ts")
  const indexText = await readFile(indexPath, "utf8")
  const indexUri = pathToFileURL(indexPath).href
  const completionPath = path.join(workspacePath, "src", "completion-probe.ts")
  const completionUri = pathToFileURL(completionPath).href
  const completionText = `import { Layer } from "effect"

Layer.`
  const workspaceUri = pathToFileURL(workspacePath).href
  await writeFile(completionPath, completionText)

  const client = new LspClient(binary, ["--lsp", "--stdio"], workspacePath)
  try {
    const initializeResult = await client.request("initialize", {
      processId: process.pid,
      clientInfo: { name: "effect-jetbrains-plugin-lsp-verifier", version: "1" },
      rootPath: workspacePath,
      rootUri: workspaceUri,
      workspaceFolders: [
        {
          uri: workspaceUri,
          name: path.basename(workspacePath),
        },
      ],
      capabilities: {
        textDocument: {
          hover: { contentFormat: ["markdown", "plaintext"] },
          completion: { completionItem: { snippetSupport: true } },
          codeAction: {
            codeActionLiteralSupport: {
              codeActionKind: {
                valueSet: ["quickfix", "refactor", "source"],
              },
            },
          },
          documentSymbol: { hierarchicalDocumentSymbolSupport: true },
          inlayHint: { dynamicRegistration: false },
        },
        workspace: {
          symbol: {},
        },
      },
    })
    console.error("healthy capabilities:", JSON.stringify(initializeResult.capabilities ?? {}, null, 2))
    client.notify("initialized", {})
    client.notify("workspace/didChangeConfiguration", { settings: {} })
    client.notify("textDocument/didOpen", {
      textDocument: {
        uri: indexUri,
        languageId: "typescript",
        version: 1,
        text: indexText,
      },
    })
    client.notify("textDocument/didOpen", {
      textDocument: {
        uri: completionUri,
        languageId: "typescript",
        version: 1,
        text: completionText,
      },
    })
    await client
      .waitForNotification("textDocument/publishDiagnostics", (params) => params.uri === indexUri, 5_000)
      .catch(() => {})

    const documentSymbols = await requestWithRetries(client, "textDocument/documentSymbol", {
      textDocument: { uri: indexUri },
    }, {
      attempts: 4,
      timeoutMs: 20_000,
      responsePredicate: (symbols) => Array.isArray(symbols) && symbols.length > 0,
    })
    const documentSymbolNames = documentSymbols.map((symbol) => symbol.name)
    assert(documentSymbolNames.includes("appLayer"), "Expected document symbols to include appLayer")
    assert(documentSymbolNames.includes("Database"), "Expected document symbols to include Database")

    const hover = await requestWithRetries(client, "textDocument/hover", {
      textDocument: { uri: indexUri },
      position: positionAt(indexText, "appLayer"),
    }, {
      attempts: 4,
      timeoutMs: 25_000,
      delayMs: 7_500,
      responsePredicate: (result) => hoverText(result).length > 0,
    })
    const hoverBody = hoverText(hover)
    assert(hoverBody.includes("Layer.Layer<Cache"), "Expected Layer hover content for appLayer")
    assert(hoverBody.includes("Show full graph"), "Expected Mermaid graph link in Layer hover")

    const completion = await requestWithRetries(client, "textDocument/completion", {
      textDocument: { uri: completionUri },
      position: positionAt(completionText, "Layer.", "Layer.".length),
    }, {
      attempts: 4,
      timeoutMs: 20_000,
      responsePredicate: (result) => {
        const items = Array.isArray(result) ? result : result?.items
        return Array.isArray(items) && items.length > 0
      },
    })
    const completionItems = Array.isArray(completion) ? completion : completion.items
    assert(completionItems.length > 0, "Expected completion items for Layer.")
    assert(completionItems.some((item) => String(item.label).includes("provide")), "Expected Effect completion for Layer.")

    const inlayHints = await requestWithRetries(client, "textDocument/inlayHint", {
      textDocument: { uri: indexUri },
      range: {
        start: { line: 0, character: 0 },
        end: (() => {
          const lines = indexText.split("\n")
          return {
            line: lines.length - 1,
            character: lines[lines.length - 1].length,
          }
        })(),
      },
    }, {
      attempts: 4,
      timeoutMs: 20_000,
      responsePredicate: (hints) => hints === null || Array.isArray(hints),
    })
    const normalizedInlayHints = Array.isArray(inlayHints) ? inlayHints : []

    const workspaceSymbols = await requestWithRetries(client, "workspace/symbol", {
      query: "Database",
    }, {
      attempts: 4,
      timeoutMs: 20_000,
      responsePredicate: (symbols) => Array.isArray(symbols) && symbols.some((symbol) => symbol.name === "Database"),
    })
    assert(Array.isArray(workspaceSymbols) && workspaceSymbols.some((symbol) => symbol.name === "Database"), "Expected workspace symbols to include Database")

    return {
      hoverHasMermaidLink: true,
      executeCommands: initializeResult.capabilities?.executeCommandProvider?.commands ?? [],
      completionItems: completionItems.length,
      inlayHints: normalizedInlayHints.length,
      documentSymbols: documentSymbolNames,
      workspaceSymbolCount: workspaceSymbols.length,
      workspacePath,
    }
  } finally {
    await client.shutdown()
  }
}

async function verifyNewDiagnosticsWorkspace(workspacePath) {
  const filePath = path.join(workspacePath, "src", "index.ts")
  const text = await readFile(filePath, "utf8")
  const uri = pathToFileURL(filePath).href
  const workspaceUri = pathToFileURL(workspacePath).href
  const client = new LspClient(binary, ["--lsp", "--stdio"], workspacePath)
  try {
    const initializeResult = await client.request("initialize", {
      processId: process.pid,
      clientInfo: { name: "effect-jetbrains-plugin-lsp-verifier", version: "1" },
      rootPath: workspacePath,
      rootUri: workspaceUri,
      workspaceFolders: [
        {
          uri: workspaceUri,
          name: path.basename(workspacePath),
        },
      ],
      capabilities: {
        textDocument: {
          codeAction: {
            codeActionLiteralSupport: {
              codeActionKind: {
                valueSet: ["quickfix", "refactor", "source"],
              },
            },
          },
        },
      },
      workspace: {},
    })
    console.error("new diagnostics capabilities:", JSON.stringify(initializeResult.capabilities ?? {}, null, 2))
    client.notify("initialized", {})
    client.notify("workspace/didChangeConfiguration", { settings: {} })
    client.notify("textDocument/didOpen", {
      textDocument: {
        uri,
        languageId: "typescript",
        version: 1,
        text,
      },
    })

    const diagnostics = await pullDiagnosticsWithRetries(
      client,
      uri,
      (candidateDiagnostics) => {
        const messages = diagnosticMessages(candidateDiagnostics)
        return messages.some((message) => message.includes("effect(catchToOrElseSucceed)")) &&
          messages.some((message) => message.includes("effect(flatMapToMap)")) &&
          messages.some((message) => message.includes("effect(redundantOrDie)")) &&
          messages.some((message) => message.includes("effect(schemaNumber)")) &&
          messages.some((message) => message.includes("effect(newSchemaClass)")) &&
          messages.some((message) => message.includes("effect(syncToSucceed)")) &&
          messages.some((message) => message.includes("effect(schemaOpaqueInstanceMember)")) &&
          messages.some((message) => message.includes("effect(preferTypedSchemaDecoder)")) &&
          messages.some((message) => message.includes("effect(preferUnsafeConstructor)")) &&
          messages.some((message) => message.includes("effect(promiseInEffectSuccess)")) &&
          messages.some((message) => message.includes("effect(floatingEffect)"))
      },
    )
    const messages = diagnosticMessages(diagnostics)
    assert(
      messages.some((message) => message.includes("effect(flatMapToMap)")),
      "Expected flatMapToMap diagnostic (published since @effect/tsgo 0.19.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(newSchemaClass)")),
      "Expected newSchemaClass diagnostic (v4-only, enabled via tsconfig diagnosticSeverity)",
    )
    assert(
      messages.some((message) => message.includes("effect(syncToSucceed)")),
      "Expected syncToSucceed diagnostic (published since @effect/tsgo 0.23.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(schemaOpaqueInstanceMember)")),
      "Expected schemaOpaqueInstanceMember diagnostic (v4-only, published since @effect/tsgo 0.22.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(preferUnsafeConstructor)")),
      "Expected preferUnsafeConstructor diagnostic (published since @effect/tsgo 0.26.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(promiseInEffectSuccess)")),
      "Expected promiseInEffectSuccess diagnostic (published since @effect/tsgo 0.26.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(preferTypedSchemaDecoder)")),
      "Expected preferTypedSchemaDecoder diagnostic (published since @effect/tsgo 0.33.0)",
    )
    assert(
      messages.some((message) => message.includes("effect(floatingEffect)")),
      "Expected floatingEffect diagnostic inside the yieldable Effect.gen context",
    )

    const codeActions = await client.request("textDocument/codeAction", {
      textDocument: { uri },
      range: fullDocumentRange(text),
      context: {
        diagnostics,
      },
    })
    const codeActionTitles = Array.isArray(codeActions)
      ? codeActions.map((action) => String(action.title ?? ""))
      : []

    assert(
      codeActionTitles.some((title) => title.includes("Effect.orElseSucceed")),
      "Expected catchToOrElseSucceed code action to mention Effect.orElseSucceed",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Effect.map")),
      "Expected flatMapToMap code action to mention Effect.map",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Schema.Finite")),
      "Expected schemaNumber code action to mention Schema.Finite",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Schema.FiniteFromString")),
      "Expected schemaNumber code action to mention Schema.FiniteFromString",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Effect.succeed") && !title.includes("orElseSucceed")),
      "Expected syncToSucceed code action to mention Effect.succeed",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Scope.makeUnsafe")),
      "Expected preferUnsafeConstructor code action to mention Scope.makeUnsafe",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Replace with decodeSync")),
      "Expected preferTypedSchemaDecoder code action to offer the typed decodeSync replacement (published since @effect/tsgo 0.33.0)",
    )
    assert(
      codeActionTitles.some((title) => title.includes("Add yield* statement")),
      "Expected floatingEffect code action to offer the yield* quick fix in yieldable contexts (published since @effect/tsgo 0.31.0)",
    )

    return {
      diagnostics: messages,
      codeActionTitles,
      executeCommands: initializeResult.capabilities?.executeCommandProvider?.commands ?? [],
      workspacePath,
    }
  } finally {
    await client.shutdown()
  }
}

async function verifyDiagnosticDirectivesWorkspace(workspacePath) {
  const filePath = path.join(workspacePath, "src", "index.ts")
  const text = await readFile(filePath, "utf8")
  const uri = pathToFileURL(filePath).href
  const workspaceUri = pathToFileURL(workspacePath).href
  const client = new LspClient(binary, ["--lsp", "--stdio"], workspacePath)
  try {
    const initializeResult = await client.request("initialize", {
      processId: process.pid,
      clientInfo: { name: "effect-jetbrains-plugin-lsp-verifier", version: "1" },
      rootPath: workspacePath,
      rootUri: workspaceUri,
      workspaceFolders: [
        {
          uri: workspaceUri,
          name: path.basename(workspacePath),
        },
      ],
      capabilities: {
        textDocument: {
          codeAction: {
            codeActionLiteralSupport: {
              codeActionKind: {
                valueSet: ["quickfix", "refactor", "source"],
              },
            },
          },
        },
      },
      workspace: {},
    })
    console.error("diagnostic directives capabilities:", JSON.stringify(initializeResult.capabilities ?? {}, null, 2))
    client.notify("initialized", {})
    client.notify("workspace/didChangeConfiguration", { settings: {} })
    client.notify("textDocument/didOpen", {
      textDocument: {
        uri,
        languageId: "typescript",
        version: 1,
        text,
      },
    })

    const diagnostics = await pullDiagnosticsWithRetries(
      client,
      uri,
      (candidateDiagnostics) => {
        const messages = diagnosticMessages(candidateDiagnostics)
        return messages.some((message) => message.includes("effect(strictEffectProvide)")) &&
          messages.some((message) => message.includes("effect(floatingEffect)"))
      },
    )
    const messages = diagnosticMessages(diagnostics)
    const strictDiagnostics = diagnosticsForRule(diagnostics, "strictEffectProvide")
    const floatingDiagnostics = diagnosticsForRule(diagnostics, "floatingEffect")

    assert(!messages.some((message) => message.includes("@effect-diagnostics directive has no effect")), "Expected all directive comments to be used")
    assert(hasDiagnosticOnLine(strictDiagnostics, lineNumberOf(text, "visible-strict")), "Expected unsuppressed strictEffectProvide diagnostic")
    assert(!hasDiagnosticOnLine(strictDiagnostics, lineNumberOf(text, "hidden-strict")), "Expected strictEffectProvide next-line directive to suppress diagnostic")
    assert(hasDiagnosticOnLine(floatingDiagnostics, lineNumberOf(text, "visible-floating")), "Expected unsuppressed floatingEffect diagnostic")
    assert(!hasDiagnosticOnLine(floatingDiagnostics, lineNumberOf(text, "hidden-floating-next-line")), "Expected floatingEffect next-line directive to suppress diagnostic")
    assert(!hasDiagnosticOnLine(floatingDiagnostics, lineNumberOf(text, "hidden-floating-section")), "Expected floatingEffect section directive to suppress diagnostic")
    assert(hasDiagnosticOnLine(floatingDiagnostics, lineNumberOf(text, "visible-floating-section")), "Expected later floatingEffect directive to re-enable diagnostic")

    return {
      diagnostics: messages,
      strictEffectProvideLines: strictDiagnostics.map((diagnostic) => diagnostic.range.start.line),
      floatingEffectLines: floatingDiagnostics.map((diagnostic) => diagnostic.range.start.line),
      executeCommands: initializeResult.capabilities?.executeCommandProvider?.commands ?? [],
      workspacePath,
    }
  } finally {
    await client.shutdown()
  }
}

async function verifyFailingWorkspace(workspacePath) {
  const filePath = path.join(workspacePath, "src", "index.ts")
  const text = await readFile(filePath, "utf8")
  const uri = pathToFileURL(filePath).href
  const workspaceUri = pathToFileURL(workspacePath).href
  const client = new LspClient(binary, ["--lsp", "--stdio"], workspacePath)
  try {
    const initializeResult = await client.request("initialize", {
      processId: process.pid,
      clientInfo: { name: "effect-jetbrains-plugin-lsp-verifier", version: "1" },
      rootPath: workspacePath,
      rootUri: workspaceUri,
      workspaceFolders: [
        {
          uri: workspaceUri,
          name: path.basename(workspacePath),
        },
      ],
      capabilities: {
        textDocument: {
          codeAction: {
            codeActionLiteralSupport: {
              codeActionKind: {
                valueSet: ["quickfix", "refactor", "source"],
              },
            },
          },
        },
      },
      workspace: {},
    })
    console.error("failing capabilities:", JSON.stringify(initializeResult.capabilities ?? {}, null, 2))
    client.notify("initialized", {})
    client.notify("workspace/didChangeConfiguration", { settings: {} })
    client.notify("textDocument/didOpen", {
      textDocument: {
        uri,
        languageId: "typescript",
        version: 1,
        text,
      },
    })

    const diagnostics = await pullDiagnosticsWithRetries(
      client,
      uri,
      (candidateDiagnostics) => candidateDiagnostics.length > 0,
    )
    assert(diagnostics.length > 0, "Expected diagnostics for failing workspace")

    const codeActions = await client.request("textDocument/codeAction", {
      textDocument: { uri },
      range: diagnostics[0].range,
      context: {
        diagnostics,
      },
    })
    assert(Array.isArray(codeActions) && codeActions.length > 0, "Expected code actions for failing workspace diagnostics")

    return {
      diagnostics: diagnostics.map((diagnostic) => ({
        code: diagnostic.code,
        message: diagnostic.message,
      })),
      codeActionTitles: codeActions.map((action) => action.title),
      workspacePath,
    }
  } finally {
    await client.shutdown()
  }
}

async function main() {
  await chmod(binary, 0o755).catch(() => {})
  const validOnlyValues = new Set(["all", "healthy", "failing", "new-diagnostics", "diagnostic-directives"])
  if (!validOnlyValues.has(only)) {
    throw new Error(`Invalid --only value: ${only}`)
  }

  // typescript@7.0.2 has gitHead 2bd066d87f5bafd315be9f40889d0a60b9e58e0b,
  // matching the native backend used for the recorded @effect/tsgo@0.37.0 smoke.
  // effect is pinned to the rc release current at refresh kickoff so the verifier does not drift
  // when the convenience dist-tag advances.
  const workspaceDependencies = ["typescript@7.0.2", "effect@4.0.0-rc.112"]
  const result = {}

  if (only === "all" || only === "healthy") {
    const healthyWorkspace = await copyFixtureWorkspace("healthy-workspace")
    await runCommand("npm", ["install", "--no-fund", "--no-audit", ...workspaceDependencies], healthyWorkspace)
    result.healthy = await verifyHealthyWorkspace(healthyWorkspace)
  }

  if (only === "all" || only === "failing") {
    const failingWorkspace = await copyFixtureWorkspace("failing-workspace")
    await runCommand("npm", ["install", "--no-fund", "--no-audit", ...workspaceDependencies], failingWorkspace)
    result.failing = await verifyFailingWorkspace(failingWorkspace)
  }

  if (only === "all" || only === "new-diagnostics") {
    const newDiagnosticsWorkspace = await copyFixtureWorkspace("new-diagnostics-workspace")
    await runCommand("npm", ["install", "--no-fund", "--no-audit", ...workspaceDependencies], newDiagnosticsWorkspace)
    result.newDiagnostics = await verifyNewDiagnosticsWorkspace(newDiagnosticsWorkspace)
  }

  if (only === "all" || only === "diagnostic-directives") {
    const diagnosticDirectivesWorkspace = await copyFixtureWorkspace("diagnostic-directives-workspace")
    await runCommand("npm", ["install", "--no-fund", "--no-audit", ...workspaceDependencies], diagnosticDirectivesWorkspace)
    result.diagnosticDirectives = await verifyDiagnosticDirectivesWorkspace(diagnosticDirectivesWorkspace)
  }

  console.log(JSON.stringify(result, null, 2))
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exit(1)
})
