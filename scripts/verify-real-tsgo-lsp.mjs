import { chmod, mkdtemp, readFile, cp } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { pathToFileURL } from "node:url"
import { spawn } from "node:child_process"

const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..")
const fixturesRoot = path.join(repoRoot, "src", "test", "testData", "fixtures", "lsp")
const REQUEST_TIMEOUT_MS = 60_000

function parseArgs(argv) {
  const args = {}
  for (let index = 0; index < argv.length; index += 1) {
    const current = argv[index]
    if (current === "--binary") {
      args.binary = argv[index + 1]
      index += 1
    }
  }
  return args
}

const { binary } = parseArgs(process.argv.slice(2))
if (!binary) {
  console.error("Usage: node scripts/verify-real-tsgo-lsp.mjs --binary /path/to/tsgo")
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
      for (const { reject } of this.pending.values()) {
        reject(error)
      }
      this.pending.clear()
      for (const waiter of this.notificationWaiters.splice(0)) {
        waiter.reject(error)
      }
    })
    this.process.on("exit", (code, signal) => {
      const error = new Error(`LSP process exited early (code=${code}, signal=${signal})\n${this.stderr}`)
      for (const { reject } of this.pending.values()) {
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

  request(method, params) {
    const id = this.nextId++
    const promise = new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(`Timed out waiting for ${method}\n${this.stderr}`))
        }
      }, REQUEST_TIMEOUT_MS)
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
    if (typeof message.id !== "undefined") {
      const pending = this.pending.get(message.id)
      if (!pending) {
        return
      }
      this.pending.delete(message.id)
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
      stdio: "inherit",
    })
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

async function verifyHealthyWorkspace(workspacePath) {
  const indexPath = path.join(workspacePath, "src", "index.ts")
  const indexText = await readFile(indexPath, "utf8")
  const indexUri = pathToFileURL(indexPath).href
  const completionUri = pathToFileURL(path.join(workspacePath, "src", "completion-probe.ts")).href
  const completionText = `import { ServiceMap } from "effect"

class CompletionProbe extends ServiceMap.`
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
    await Promise.race([
      client.waitForNotification("textDocument/publishDiagnostics", (params) => params.uri === indexUri, 10_000),
      new Promise((resolve) => setTimeout(resolve, 5_000)),
    ])

    const hover = await client.request("textDocument/hover", {
      textDocument: { uri: indexUri },
      position: positionAt(indexText, "appLayer"),
    })
    const hoverBody = hoverText(hover)
    assert(hoverBody.includes("Layer.Layer<Cache"), "Expected Layer hover content for appLayer")
    assert(hoverBody.includes("Show full graph"), "Expected Mermaid graph link in Layer hover")

    const completion = await client.request("textDocument/completion", {
      textDocument: { uri: completionUri },
      position: positionAt(completionText, "ServiceMap.", "ServiceMap.".length),
    })
    const completionItems = Array.isArray(completion) ? completion : completion.items
    assert(completionItems.length > 0, "Expected completion items for ServiceMap.")
    assert(completionItems.some((item) => String(item.label).includes("Service<CompletionProbe")), "Expected Effect completion for ServiceMap.")

    const inlayHints = await client.request("textDocument/inlayHint", {
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
    })
    assert(Array.isArray(inlayHints) && inlayHints.length > 0, "Expected inlay hints from healthy workspace")

    const documentSymbols = await client.request("textDocument/documentSymbol", {
      textDocument: { uri: indexUri },
    })
    const documentSymbolNames = documentSymbols.map((symbol) => symbol.name)
    assert(documentSymbolNames.includes("appLayer"), "Expected document symbols to include appLayer")
    assert(documentSymbolNames.includes("Database"), "Expected document symbols to include Database")

    const workspaceSymbols = await client.request("workspace/symbol", {
      query: "Database",
    })
    assert(Array.isArray(workspaceSymbols) && workspaceSymbols.some((symbol) => symbol.name === "Database"), "Expected workspace symbols to include Database")

    return {
      hoverHasMermaidLink: true,
      completionItems: completionItems.length,
      inlayHints: inlayHints.length,
      documentSymbols: documentSymbolNames,
      workspaceSymbolCount: workspaceSymbols.length,
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

    const diagnosticsParams = await client.waitForNotification(
      "textDocument/publishDiagnostics",
      (params) => params.uri === uri && Array.isArray(params.diagnostics) && params.diagnostics.length > 0,
      10_000,
    )
    assert(diagnosticsParams.diagnostics.length > 0, "Expected diagnostics for failing workspace")

    const codeActions = await client.request("textDocument/codeAction", {
      textDocument: { uri },
      range: diagnosticsParams.diagnostics[0].range,
      context: {
        diagnostics: diagnosticsParams.diagnostics,
      },
    })
    assert(Array.isArray(codeActions) && codeActions.length > 0, "Expected code actions for failing workspace diagnostics")

    return {
      diagnostics: diagnosticsParams.diagnostics.map((diagnostic) => ({
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

  const healthyWorkspace = await copyFixtureWorkspace("healthy-workspace")
  const failingWorkspace = await copyFixtureWorkspace("failing-workspace")

  await runCommand("npm", ["install", "--no-fund", "--no-audit", "effect", "@effect/language-service"], healthyWorkspace)
  await runCommand("npm", ["install", "--no-fund", "--no-audit", "effect", "@effect/language-service"], failingWorkspace)

  const healthy = await verifyHealthyWorkspace(healthyWorkspace)
  const failing = await verifyFailingWorkspace(failingWorkspace)

  console.log(JSON.stringify({ healthy, failing }, null, 2))
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exit(1)
})
