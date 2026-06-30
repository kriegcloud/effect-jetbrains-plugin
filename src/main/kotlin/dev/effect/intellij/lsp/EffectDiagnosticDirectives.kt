package dev.effect.intellij.lsp

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
                } else if (skipFileRules.isEmpty()) {
                    sectionDirectives += directive
                }
            }

            return EffectDiagnosticDirectiveSet(byLine, fileLevel, sectionDirectives)
        }

        private fun collectDirectives(sourceText: String): List<EffectDiagnosticDirective> =
            sourceText
                .split('\n')
                .mapIndexedNotNull { lineNumber, line ->
                    val match = directivePattern.find(line) ?: return@mapIndexedNotNull null
                    val rules = ruleSeverityPattern.findAll(match.groupValues[2])
                        .mapNotNull { ruleMatch ->
                            val severity = parseSeverity(ruleMatch.groupValues[2]) ?: return@mapNotNull null
                            EffectDiagnosticDirectiveRule(ruleMatch.groupValues[1], severity)
                        }
                        .toList()

                    if (rules.isEmpty()) {
                        null
                    } else {
                        EffectDiagnosticDirective(
                            line = lineNumber,
                            isNextLine = match.groupValues[1] == "-next-line",
                            rules = rules,
                        )
                    }
                }
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
    message
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
        diagnostic.message = message
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
