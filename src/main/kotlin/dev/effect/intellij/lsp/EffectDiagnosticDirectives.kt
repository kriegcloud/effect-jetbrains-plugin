package dev.effect.intellij.lsp

import com.intellij.lang.javascript.DialectOptionHolder
import com.intellij.lang.javascript.JSElementTypes
import com.intellij.lang.javascript.JSFlexAdapter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Key
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

internal enum class EffectDiagnosticDirectiveSeverity {
    OFF,
    WARNING,
    ERROR,
    SUGGESTION,
    MESSAGE,
    SKIP_FILE,
}

internal data class EffectDiagnosticDirectiveRule(
    val rule: String,
    val severity: EffectDiagnosticDirectiveSeverity,
)

private data class EffectDiagnosticDirective(
    val line: Int,
    val isNextLine: Boolean,
    val rules: List<EffectDiagnosticDirectiveRule>,
) {
    val affectedLine: Int
        get() = if (isNextLine) line + 1 else line
}

internal class EffectDiagnosticDirectiveSet private constructor(
    private val byLine: Map<Int, List<EffectDiagnosticDirective>>,
    private val fileLevel: List<EffectDiagnosticDirectiveRule>,
    private val sectionDirectives: List<EffectDiagnosticDirective>,
) {
    fun effectiveSeverity(ruleName: String, line: Int): EffectDiagnosticDirectiveSeverity? {
        fileLevel.firstOrNull { it.matches(ruleName) }?.let { return EffectDiagnosticDirectiveSeverity.SKIP_FILE }

        byLine[line]?.forEach { directive ->
            directive.rules.firstOrNull { it.matches(ruleName) }?.let { return it.severity }
        }

        sectionDirectives.asReversed().forEach { directive ->
            if (directive.line <= line) {
                directive.rules.firstOrNull { it.matches(ruleName) }?.let { return it.severity }
            }
        }

        return null
    }

    companion object {
        private val empty = EffectDiagnosticDirectiveSet(emptyMap(), emptyList(), emptyList())

        fun parse(sourceText: String): EffectDiagnosticDirectiveSet {
            val directives = collectDirectives(sourceText)
            if (directives.isEmpty()) {
                return empty
            }

            val byLine = linkedMapOf<Int, MutableList<EffectDiagnosticDirective>>()
            val fileLevel = mutableListOf<EffectDiagnosticDirectiveRule>()
            val sectionDirectives = mutableListOf<EffectDiagnosticDirective>()

            directives.forEach { directive ->
                val skipFileRules = directive.rules.filter { it.severity == EffectDiagnosticDirectiveSeverity.SKIP_FILE }
                fileLevel += skipFileRules

                if (directive.isNextLine) {
                    byLine.getOrPut(directive.affectedLine) { mutableListOf() } += directive
                } else {
                    val sectionRules = directive.rules.filter { it.severity != EffectDiagnosticDirectiveSeverity.SKIP_FILE }
                    if (sectionRules.isNotEmpty()) sectionDirectives += directive.copy(rules = sectionRules)
                }
            }

            return EffectDiagnosticDirectiveSet(byLine, fileLevel, sectionDirectives)
        }

        private fun collectDirectives(sourceText: String): List<EffectDiagnosticDirective> {
            if (!sourceText.contains("@effect-diagnostics")) return emptyList()
            val lexer = JSFlexAdapter(DialectOptionHolder.TS)
            lexer.start(sourceText)
            val directives = mutableListOf<EffectDiagnosticDirective>()
            var lineNumber = 0
            while (lexer.tokenType != null) {
                val token = sourceText.subSequence(lexer.tokenStart, lexer.tokenEnd)
                if (JSElementTypes.COMMENTS.contains(lexer.tokenType)) {
                    token.split('\n').forEachIndexed { relativeLine, line ->
                        val match = directivePattern.find(line) ?: return@forEachIndexed
                        val rules = ruleSeverityPattern.findAll(match.groupValues[2]).mapNotNull { ruleMatch ->
                            val severity = parseSeverity(ruleMatch.groupValues[2]) ?: return@mapNotNull null
                            EffectDiagnosticDirectiveRule(ruleMatch.groupValues[1], severity)
                        }.toList()
                        if (rules.isNotEmpty()) {
                            directives += EffectDiagnosticDirective(lineNumber + relativeLine, match.groupValues[1] == "-next-line", rules)
                        }
                    }
                }
                lineNumber += token.count { it == '\n' }
                lexer.advance()
            }
            return directives
        }
    }
}

private data class CachedEffectDirectives(val stamp: Long, val directives: EffectDiagnosticDirectiveSet)
private val directiveCacheKey = Key.create<CachedEffectDirectives>("effect.diagnostic.directives")

/** Call under read access, so the stamp and text belong to the same document snapshot. */
internal fun effectDiagnosticDirectives(document: Document): EffectDiagnosticDirectiveSet = synchronized(document) {
    val stamp = document.modificationStamp
    document.getUserData(directiveCacheKey)?.takeIf { it.stamp == stamp }?.directives
        ?: EffectDiagnosticDirectiveSet.parse(document.text).also {
            document.putUserData(directiveCacheKey, CachedEffectDirectives(stamp, it))
        }
}

internal fun effectDirectiveSeverityForDiagnostic(
    sourceText: String,
    diagnosticLine: Int,
    diagnostic: Diagnostic,
): EffectDiagnosticDirectiveSeverity? =
    diagnostic.effectDiagnosticRuleName()
        ?.let { ruleName -> EffectDiagnosticDirectiveSet.parse(sourceText).effectiveSeverity(ruleName, diagnosticLine) }

internal fun Diagnostic.effectDiagnosticRuleName(): String? =
    EffectLsp4jDiagnosticMessage.text(this)
        ?.let { diagnosticRulePattern.find(it) }
        ?.groupValues
        ?.getOrNull(1)

internal fun Diagnostic.withEffectDirectiveSeverity(severity: EffectDiagnosticDirectiveSeverity): Diagnostic =
    copy().also { diagnostic ->
        diagnostic.severity = when (severity) {
            EffectDiagnosticDirectiveSeverity.ERROR -> DiagnosticSeverity.Error
            EffectDiagnosticDirectiveSeverity.WARNING -> DiagnosticSeverity.Warning
            EffectDiagnosticDirectiveSeverity.SUGGESTION -> DiagnosticSeverity.Hint
            EffectDiagnosticDirectiveSeverity.MESSAGE -> DiagnosticSeverity.Information
            EffectDiagnosticDirectiveSeverity.OFF,
            EffectDiagnosticDirectiveSeverity.SKIP_FILE,
            -> diagnostic.severity
        }
    }

private fun Diagnostic.copy(): Diagnostic =
    Diagnostic().also { diagnostic ->
        diagnostic.range = range
        diagnostic.severity = severity
        diagnostic.code = code
        diagnostic.codeDescription = codeDescription
        diagnostic.source = source
        EffectLsp4jDiagnosticMessage.copy(this, diagnostic)
        diagnostic.tags = tags
        diagnostic.relatedInformation = relatedInformation
        diagnostic.data = data
    }

private fun EffectDiagnosticDirectiveRule.matches(ruleName: String): Boolean {
    val directiveRule = rule.normalizedRuleName()
    val diagnosticRule = ruleName.normalizedRuleName()
    return directiveRule == "*" ||
        directiveRule == diagnosticRule ||
        directiveRule.substringAfterLast('/') == diagnosticRule ||
        directiveRule == diagnosticRule.substringAfterLast('/')
}

private fun String.normalizedRuleName(): String = trim().lowercase()

private fun parseSeverity(raw: String): EffectDiagnosticDirectiveSeverity? =
    when (raw.trim().lowercase()) {
        "off" -> EffectDiagnosticDirectiveSeverity.OFF
        "warning", "warn" -> EffectDiagnosticDirectiveSeverity.WARNING
        "error" -> EffectDiagnosticDirectiveSeverity.ERROR
        "suggestion" -> EffectDiagnosticDirectiveSeverity.SUGGESTION
        "message" -> EffectDiagnosticDirectiveSeverity.MESSAGE
        "skip-file" -> EffectDiagnosticDirectiveSeverity.SKIP_FILE
        else -> null
    }

private val directivePattern = Regex("""@effect-diagnostics(-next-line)?\s+([A-Za-z0-9_*/.\-:]+(?:\s+[A-Za-z0-9_*/.\-:]+)*)""")
private val ruleSeverityPattern = Regex("""([A-Za-z0-9_*/.\-]+):([A-Za-z]+(?:-[A-Za-z]+)?)""")
private val diagnosticRulePattern = Regex("""effect\(([^)]+)\)\s*$""")
