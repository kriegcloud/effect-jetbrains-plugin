package dev.effect.intellij.lsp.fixall

import com.intellij.openapi.progress.ProgressManager
import dev.effect.intellij.lsp.EffectLsp4jDiagnosticMessage
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.TextEdit

/**
 * The document and language server one "Fix all" run talks to. [apply] and [restore] change the
 * editor document; the platform forwards each change to the server (`textDocument/didChange` is sent
 * synchronously from the document listener), so the next [pullDiagnostics] reflects it.
 */
internal interface EffectFixAllSession {
    fun currentText(): String

    fun pullDiagnostics(): List<Diagnostic>

    fun requestQuickFixes(diagnostics: List<Diagnostic>): List<CodeAction>

    fun apply(edits: List<TextEdit>)

    fun restore(text: String)
}

/** What a complete multi-pass run did. */
internal data class EffectFixAllRunResult(
    /** Diagnostics of the rule reported before the first pass. */
    val initialDiagnostics: Int,
    /** Fixes applied and kept. */
    val appliedFixes: Int,
    /** Diagnostics of the rule still reported after the last pass. */
    val remainingDiagnostics: Int,
    /** Sites whose fix was rolled back because it introduced new errors. */
    val rejectedFixes: Int,
    /** The plan of the last pass that found nothing to apply, for explaining why nothing changed. */
    val lastEmptyPlan: EffectFixAllPlan?,
    val passes: Int,
)

/**
 * Applies the chosen fix to every matching diagnostic of its rule, repeating until nothing changes.
 *
 * Each pass pulls the current diagnostics, requests their quick fixes, merges the non-overlapping edits
 * ([planFixAll]) and applies them; the next pass picks up sites whose edits overlapped, plus findings a
 * rewrite exposes (for example a nested pipe chain that only becomes flat after its parent is fixed).
 *
 * Fixes are computed independently by the server, so a merged batch can be wrong even when each fix is
 * right on its own (two hoisted declarations given the same generated name in one scope), and some
 * upstream fixes are wrong on their own. After every pass the error-severity diagnostics are compared
 * with those before it; a pass that introduces any new error is undone. A rejected batch is retried one
 * fix at a time, and a single fix that still introduces an error is skipped for the rest of the run.
 */
internal fun runFixAllPasses(
    request: EffectFixAllRequest,
    documentUri: String,
    session: EffectFixAllSession,
    maxPasses: Int = DEFAULT_MAX_FIX_ALL_PASSES,
): EffectFixAllRunResult {
    var diagnostics = session.pullDiagnostics()
    val initial = selectFixAllDiagnostics(diagnostics, session.currentText(), request.ruleKey, documentUri).size
    if (initial == 0) {
        return EffectFixAllRunResult(0, 0, 0, 0, null, 0)
    }

    var oneAtATime = false
    val skippedSites = HashSet<String>()
    val rejectedSites = HashSet<String>()
    var applied = 0
    var passes = 0
    var lastEmptyPlan: EffectFixAllPlan? = null

    while (passes < maxPasses) {
        ProgressManager.checkCanceled()
        val text = session.currentText()
        val sites = siteKeys(diagnostics, text)
        val candidates = selectFixAllDiagnostics(diagnostics, text, request.ruleKey, documentUri)
            .filter { sites.getValue(it.fixAllKey()) !in skippedSites }
        if (candidates.isEmpty()) {
            break
        }
        passes += 1
        val batch = if (oneAtATime) candidates.take(1) else candidates
        val plan = planFixAll(request, documentUri, batch, session.requestQuickFixes(batch), EffectTextLineIndex(text)::rangeOf)
        if (plan.fixedDiagnostics == 0 || plan.edits.isEmpty()) {
            if (!oneAtATime) {
                lastEmptyPlan = plan
                break
            }
            // One site without a usable fix: move on to the next one.
            skippedSites += sites.getValue(batch.single().fixAllKey())
            continue
        }

        val errorsBefore = errorSignatures(diagnostics)
        val after = try {
            session.apply(plan.edits)
            ProgressManager.checkCanceled()
            if (session.currentText() == text) {
                // The fix rewrote the text to itself; repeating it can never make progress.
                if (!oneAtATime) {
                    break
                }
                skippedSites += sites.getValue(batch.single().fixAllKey())
                continue
            }
            session.pullDiagnostics().also { ProgressManager.checkCanceled() }
        } catch (error: Throwable) {
            // Unvalidated: never leave this pass in the document. Earlier, validated passes stay.
            session.restore(text)
            throw error
        }
        if (introducesErrors(errorsBefore, errorSignatures(after))) {
            session.restore(text)
            if (!oneAtATime) {
                // Only a one-fix pass identifies the culprit: retry the batch one site at a time.
                oneAtATime = true
            } else {
                val site = sites.getValue(batch.single().fixAllKey())
                rejectedSites += site
                skippedSites += site
            }
            // The text is back to what `diagnostics` describes.
            continue
        }
        applied += plan.fixedDiagnostics
        diagnostics = after
    }

    val remaining = selectFixAllDiagnostics(diagnostics, session.currentText(), request.ruleKey, documentUri).size
    return EffectFixAllRunResult(initial, applied, remaining, rejectedSites.size, lastEmptyPlan.takeIf { applied == 0 }, passes)
}

internal const val DEFAULT_MAX_FIX_ALL_PASSES = 64

/**
 * Identifies a diagnostic site by what it covers rather than where, so a site skipped in one pass is
 * still recognized after earlier fixes moved or reformatted its line: message, covered text, and the
 * occurrence index among identical sites.
 */
private fun siteKeys(diagnostics: List<Diagnostic>, text: String): Map<EffectDiagnosticKey, String> {
    val lineIndex = EffectTextLineIndex(text)
    val seen = HashMap<String, Int>()
    return diagnostics.sortedWith(diagnosticOrder).associate { diagnostic ->
        val range = diagnostic.range?.let(lineIndex::rangeOf)
        val covered = range?.let { text.substring(it.startOffset, it.endOffset) }.orEmpty()
        val base = "${diagnostic.effectDiagnosticMessageOrEmpty()}\u0000$covered"
        val occurrence = seen.merge(base, 1, Int::plus)!!
        diagnostic.fixAllKey() to "$base\u0000$occurrence"
    }
}

private fun Diagnostic.effectDiagnosticMessageOrEmpty(): String = EffectLsp4jDiagnosticMessage.text(this).orEmpty()

/** Error-severity diagnostics as a multiset of code + message; positions shift with every edit. */
private fun errorSignatures(diagnostics: List<Diagnostic>): Map<String, Int> =
    diagnostics.asSequence()
        .filter { it.severity == DiagnosticSeverity.Error }
        .map { "${it.code?.let { code -> if (code.isRight) code.right.toString() else code.left }}\u0000${it.effectDiagnosticMessageOrEmpty()}" }
        .groupingBy { it }
        .eachCount()

private fun introducesErrors(before: Map<String, Int>, after: Map<String, Int>): Boolean =
    after.any { (signature, count) -> count > (before[signature] ?: 0) }
