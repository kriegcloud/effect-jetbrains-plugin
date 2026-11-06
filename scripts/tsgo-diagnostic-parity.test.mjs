import assert from "node:assert/strict"
import test from "node:test"
import path from "node:path"
import { assertDiagnosticParity } from "./tsgo-diagnostic-parity.mjs"

const cwd = path.resolve("parity-fixture")
const finding = { file: "src/collision.ts", name: "yieldInCallArgument", line: 3, severity: "warning" }
const baseline = [finding, { ...finding, line: 7 }]

test("parity preserves duplicate occurrences and normalizes source paths and order", () => {
  assertDiagnosticParity([...baseline, finding], [finding, ...baseline].reverse().map((item) =>
    ({ ...item, file: path.join(cwd, item.file) })), cwd)
})

test("parity rejects moved files and added or removed occurrences with unchanged rule names", () => {
  for (const mutated of [
    [{ ...finding, file: "src/harmful.ts" }, baseline[1]],
    [...baseline, finding],
    baseline.slice(1),
    [{ ...finding, line: 4 }, baseline[1]],
    [{ ...finding, severity: "error" }, baseline[1]],
  ]) {
    assert.deepEqual([...new Set(mutated.map((item) => item.name))], [finding.name])
    assert.throws(() => assertDiagnosticParity(baseline, mutated, cwd), /occurrence parity/)
  }
})
