package dev.effect.intellij.lsp.fixall

import com.intellij.openapi.util.TextRange
import dev.effect.intellij.lsp.EffectDiagnosticDirectiveSeverity
import dev.effect.intellij.lsp.effectDirectiveSeverityForDiagnostic
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * The edits chosen for one fix-all run, plus what was left out and why.
 *
 * `@effect/tsgo` has no server-side "fix all" for Effect fixables (its `CodeFixProvider` only gets a
 * combined-fix entry when `GetAllCodeActions` is set, which the Effect provider does not do), so the
 * plugin asks for every per-diagnostic fix in one `textDocument/codeAction` request and merges the
 * non-overlapping ones here.
 */
internal data class EffectFixAllPlan(
    /** Edits that apply together against the document version the fixes were computed for. */
    val edits: List<TextEdit>,
    /** Diagnostics whose fix is part of [edits]. */
    val fixedDiagnostics: Int,
    /** Diagnostics of the rule that offered no fix with the requested title (or title shape). */
    val unmatchedDiagnostics: Int,
    /** Diagnostics whose fix overlapped an already-accepted edit; a second run picks them up. */
    val overlappingDiagnostics: Int,
    /** Diagnostics whose fix touched other documents, carried ranges outside the text, or had no edits. */
    val unsupportedDiagnostics: Int,
) {
    val skippedDiagnostics: Int
        get() = unmatchedDiagnostics + overlappingDiagnostics + unsupportedDiagnostics

    companion object {
        val EMPTY = EffectFixAllPlan(emptyList(), 0, 0, 0, 0)
    }
}

/**
 * Picks the diagnostics "Fix all" should repair: every diagnostic of the requested rule that is not
 * silenced by an `@effect-diagnostics` directive (the same filter the annotator applies before it shows
 * a problem), deduplicated by range and code.
 */
internal fun selectFixAllDiagnostics(
    diagnostics: List<Diagnostic>,
    sourceText: String,
    ruleKey: String,
): List<Diagnostic> =
    diagnostics
        .asSequence()
        .filter { diagnostic -> diagnostic.range != null && diagnostic.effectFixAllRuleKey() == ruleKey }
        .filter { diagnostic ->
            when (effectDirectiveSeverityForDiagnostic(sourceText, diagnostic.range.start.line, diagnostic)) {
                EffectDiagnosticDirectiveSeverity.OFF,
                EffectDiagnosticDirectiveSeverity.SKIP_FILE,
                -> false
                else -> true
            }
        }
        .distinctBy { it.fixAllKey() }
        .sortedWith(diagnosticOrder)
        .toList()

/**
 * Chooses one fix per diagnostic and merges their edits.
 *
 * A fix matches when its title equals the chosen fix's title, or when exactly one fix at the site has a
 * title that differs from it only inside quoted segments: fixables such as `Remove redundant identifier
 * 'x'` or `Convert to Effect.fn("name")` embed a site-specific name in an otherwise fixed title. Titles
 * that differ anywhere else are treated as different fixables (a rule can offer mutually exclusive
 * rewrites per site, e.g. `Catch all errors with Effect.catch` vs `Catch unexpected errors with
 * Effect.catchTag`), and a site where several fixes share the shape (`Convert to Effect.fn("a")` next to
 * `Convert to Effect.fn("b")` when inferred and suggested span names are both enabled) is ambiguous; both
 * stay unmatched rather than receiving a rewrite the user did not pick.
 *
 * Diagnostics are visited in document order; a fix whose edits overlap edits already accepted is left
 * for a later run instead of corrupting the text.
 */
internal fun planFixAll(
    request: EffectFixAllRequest,
    documentUri: String,
    diagnostics: List<Diagnostic>,
    codeActions: List<CodeAction>,
    rangeToOffsets: (Range) -> TextRange?,
): EffectFixAllPlan {
    val codemodsByDiagnostic: Map<EffectDiagnosticKey, List<CodeAction>> = codeActions
        .asSequence()
        .filter { action -> action.isQuickFixKind() && isEffectCodemodFixTitle(action.title) }
        .flatMap { action -> action.diagnostics.orEmpty().map { diagnostic -> diagnostic.fixAllKey() to action } }
        .groupBy({ it.first }, { it.second })

    val requestedShape = fixTitleShape(request.fixTitle)
    val acceptedRanges = mutableListOf<TextRange>()
    val edits = mutableListOf<TextEdit>()
    var fixed = 0
    var unmatched = 0
    var overlapping = 0
    var unsupported = 0

    for (diagnostic in diagnostics.sortedWith(diagnosticOrder)) {
        val candidates = codemodsByDiagnostic[diagnostic.fixAllKey()].orEmpty()
        val action = candidates.firstOrNull { it.title.trim() == request.fixTitle }
            ?: candidates.filter { fixTitleShape(it.title) == requestedShape }.singleOrNull()
        if (action == null) {
            unmatched += 1
            continue
        }

        val textEdits = action.textEditsFor(documentUri)
        if (textEdits.isNullOrEmpty()) {
            unsupported += 1
            continue
        }

        val resolved = textEdits.map { edit -> edit.range?.let(rangeToOffsets)?.let { range -> ResolvedEdit(range, edit) } }
        if (resolved.any { it == null }) {
            unsupported += 1
            continue
        }
        val normalized = normalizeActionEdits(resolved.filterNotNull())
        if (normalized == null) {
            unsupported += 1
            continue
        }
        if (normalized.any { candidate -> acceptedRanges.any { it.conflicts(candidate.range) } }) {
            overlapping += 1
            continue
        }

        acceptedRanges += normalized.map { it.range }
        edits += normalized.map { it.edit }
        fixed += 1
    }

    return EffectFixAllPlan(
        edits = edits,
        fixedDiagnostics = fixed,
        unmatchedDiagnostics = unmatched,
        overlappingDiagnostics = overlapping,
        unsupportedDiagnostics = unsupported,
    )
}

/**
 * The text edits of [this] action for [documentUri], or `null` when the action edits other documents
 * or carries resource operations: Effect fixables rewrite the file they report on, so anything else is
 * treated as unsupported rather than partially applied. `documentChanges` wins over `changes` when a
 * server sends both, as the LSP specification requires.
 */
internal fun CodeAction.textEditsFor(documentUri: String): List<TextEdit>? {
    val workspaceEdit = edit ?: return null
    val collected = mutableListOf<TextEdit>()

    val documentChanges = workspaceEdit.documentChanges
    if (documentChanges != null) {
        documentChanges.forEach { change ->
            if (!change.isLeft) {
                return null
            }
            val documentEdit = change.left
            if (documentEdit.textDocument?.uri != documentUri) {
                return null
            }
            collected += documentEdit.edits.orEmpty()
        }
        return collected
    }

    workspaceEdit.changes?.forEach { (uri, textEdits) ->
        if (uri != documentUri) {
            return null
        }
        collected += textEdits.orEmpty()
    }
    return collected
}

/**
 * Collapses a quick-fix title to the shape that is stable across sites: everything inside single or
 * double quotes is blanked, so `Remove redundant identifier 'a'` and `Remove redundant identifier 'b'`
 * (or `Convert to Effect.fn("first")` / `Convert to Effect.fn("second")`) compare equal while
 * `Replace with Effect.ignore` and `Replace with Effect.ignoreCause` do not.
 */
internal fun fixTitleShape(title: String?): String =
    quotedSegment.replace(title.orEmpty().trim(), "\u0000")

/**
 * Converts LSP positions (zero-based line, UTF-16 column) to offsets in [text] the way the LSP
 * document mapping does for `\n`-separated IDE documents; `null` for positions outside the text.
 */
internal class EffectTextLineIndex(private val text: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    fun offsetOf(line: Int, character: Int): Int? {
        if (line < 0 || line >= lineStarts.size || character < 0) {
            return null
        }
        val lineStart = lineStarts[line]
        val lineEnd = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
        val offset = lineStart + character
        return offset.takeIf { it <= lineEnd }
    }

    fun rangeOf(range: Range): TextRange? {
        val start = offsetOf(range.start.line, range.start.character) ?: return null
        val end = offsetOf(range.end.line, range.end.character) ?: return null
        return if (end < start) null else TextRange(start, end)
    }
}

internal data class ResolvedEdit(val range: TextRange, val edit: TextEdit)

/**
 * Brings one action's edits into a form that can be mixed with other actions' edits.
 *
 * `@effect/tsgo`'s change tracker emits insertions and replacements as separate edits and never merges
 * them, so one fix routinely carries several edits that start at the same offset: a delete `[P, E)` plus
 * an insertion at `P` (`unnecessaryArrowBlock`, `multipleEffectProvide`), or two insertions at `P`
 * (`layerMergeAllWithDependencies` inserts `.pipe(Layer.provideMerge(x)` and `)` separately). The platform
 * applies a single quick fix correctly only because it keeps the server's order through a stable sort and
 * applies the edits backwards, which yields the insertions concatenated in server order followed by the
 * replacement text. Each same-start group is merged here into that one replacement so the combined edit
 * list no longer depends on edit order. A group with two or more non-empty ranges is malformed, and
 * `null` marks the action as unsupported; the same goes for edits that overlap inside one action.
 */
internal fun normalizeActionEdits(edits: List<ResolvedEdit>): List<ResolvedEdit>? {
    val merged = edits
        .groupBy { it.range.startOffset }
        .values
        .map { group ->
            if (group.size == 1) {
                return@map group.single()
            }
            val replacements = group.filter { !it.range.isEmpty }
            if (replacements.size > 1) {
                return null
            }
            val insertions = group.filter { it.range.isEmpty }
            val replacement = replacements.singleOrNull()
            val anchor = replacement ?: insertions.first()
            val newText = insertions.joinToString("") { it.edit.newText.orEmpty() } + replacement?.edit?.newText.orEmpty()
            ResolvedEdit(anchor.range, TextEdit(anchor.edit.range, newText))
        }
        .sortedBy { it.range.startOffset }

    for (index in 1 until merged.size) {
        if (merged[index - 1].range.endOffset > merged[index].range.startOffset) {
            return null
        }
    }
    return merged
}

private val quotedSegment = Regex("""'[^']*'|"[^"]*"""")

private val diagnosticOrder: Comparator<Diagnostic> =
    compareBy({ it.range?.start?.line ?: -1 }, { it.range?.start?.character ?: -1 })

/**
 * Edits of different fixes conflict when they overlap or start at the same offset: the platform
 * applies LSP edits from the end of the document backwards, and two edits anchored at one offset have
 * no defined order.
 */
private fun TextRange.conflicts(other: TextRange): Boolean =
    startOffset == other.startOffset ||
        (startOffset < other.endOffset && other.startOffset < endOffset)
