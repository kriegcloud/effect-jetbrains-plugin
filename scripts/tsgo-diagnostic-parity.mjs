import assert from "node:assert/strict"
import path from "node:path"

// Keep every occurrence: sorting tuples compares a multiset, including duplicates,
// while normalizing absolute CLI paths and relative LSP paths to the same source.
export function diagnosticMultiset(diagnostics, cwd) {
  return diagnostics.map(({ file, name, line, severity }) => {
    const relative = path.relative(cwd, path.resolve(cwd, file.replaceAll("\\", "/"))).replaceAll("\\", "/")
    return JSON.stringify([relative, name, line, severity])
  }).sort()
}

export function assertDiagnosticParity(cli, lsp, cwd, fixture = "fixture") {
  assert.deepEqual(diagnosticMultiset(cli, cwd), diagnosticMultiset(lsp, cwd),
    `${fixture}: CLI/LSP per-file diagnostic occurrence parity (rule, line, severity)`)
}
