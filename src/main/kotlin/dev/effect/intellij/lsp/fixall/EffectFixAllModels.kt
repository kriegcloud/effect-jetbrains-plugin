package dev.effect.intellij.lsp.fixall

import dev.effect.intellij.lsp.effectDiagnosticRuleName
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic

/**
 * Identity of one published diagnostic inside a document: its range plus its code.
 *
 * `@effect/tsgo` echoes the diagnostics it received in `textDocument/codeAction`'s
 * `context.diagnostics` back on each returned quick fix (`CodeAction.diagnostics`), so this key is
 * what links a returned fix to the diagnostic it repairs.
 */
internal data class EffectDiagnosticKey(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val code: String?,
)

/**
 * What the user asked for when choosing "Fix all '…' problems in file" on one quick fix: the
 * rule the diagnostic belongs to and the title of the fix that was chosen.
 */
internal data class EffectFixAllRequest(
    val ruleKey: String,
    val ruleDisplayName: String,
    val fixTitle: String,
)

/** `@effect/tsgo` reserves this code range for Effect rules (`rule.IsEffectCode`). */
internal const val EFFECT_DIAGNOSTIC_CODE_MIN = 377000
internal const val EFFECT_DIAGNOSTIC_CODE_MAX = 377999

/** The directive-insertion fixes `@effect/tsgo` adds to every Effect diagnostic; they are not codemods. */
private val disableDirectiveFixTitle = Regex("""^Disable \S+ for (?:this line|entire file)$""")

internal fun isEffectCodemodFixTitle(title: String?): Boolean =
    !title.isNullOrBlank() && !disableDirectiveFixTitle.matches(title.trim())

internal fun Diagnostic.effectDiagnosticCode(): Int? {
    val code = code ?: return null
    return if (code.isRight) code.right else code.left?.toIntOrNull()
}

internal fun Diagnostic.isEffectDiagnostic(): Boolean =
    effectDiagnosticRuleName() != null ||
        effectDiagnosticCode()?.let { it in EFFECT_DIAGNOSTIC_CODE_MIN..EFFECT_DIAGNOSTIC_CODE_MAX } == true

/** Identity used to find "the same problem" elsewhere in the file: the rule name, or the Effect code. */
internal fun Diagnostic.effectFixAllRuleKey(): String? =
    effectDiagnosticRuleName()?.trim()?.takeIf { it.isNotEmpty() }?.let { "rule:$it" }
        ?: effectDiagnosticCode()
            ?.takeIf { it in EFFECT_DIAGNOSTIC_CODE_MIN..EFFECT_DIAGNOSTIC_CODE_MAX }
            ?.let { "code:$it" }

internal fun Diagnostic.effectRuleDisplayName(): String? =
    effectDiagnosticRuleName()?.trim()?.takeIf { it.isNotEmpty() }
        ?: effectDiagnosticCode()?.toString()

internal fun Diagnostic.fixAllKey(): EffectDiagnosticKey =
    EffectDiagnosticKey(
        startLine = range?.start?.line ?: -1,
        startCharacter = range?.start?.character ?: -1,
        endLine = range?.end?.line ?: -1,
        endCharacter = range?.end?.character ?: -1,
        code = code?.let { if (it.isRight) it.right.toString() else it.left },
    )

internal fun CodeAction.isQuickFixKind(): Boolean {
    val kind = kind
    return kind.isNullOrEmpty() || kind == CodeActionKind.QuickFix || kind.startsWith(CodeActionKind.QuickFix + ".")
}

/**
 * Builds the fix-all request for a quick fix shown on [diagnostic], or `null` when the fix is not an
 * Effect codemod (directive-insertion fixes, non-Effect diagnostics, unresolved lazy fixes).
 */
internal fun effectFixAllRequest(diagnostic: Diagnostic, fixTitle: String?): EffectFixAllRequest? {
    if (!isEffectCodemodFixTitle(fixTitle)) {
        return null
    }
    val ruleKey = diagnostic.effectFixAllRuleKey() ?: return null
    return EffectFixAllRequest(
        ruleKey = ruleKey,
        ruleDisplayName = diagnostic.effectRuleDisplayName() ?: ruleKey,
        fixTitle = fixTitle!!.trim(),
    )
}
