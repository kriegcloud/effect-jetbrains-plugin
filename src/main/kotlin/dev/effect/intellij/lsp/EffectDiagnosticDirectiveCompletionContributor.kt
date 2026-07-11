package dev.effect.intellij.lsp

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.effect.intellij.core.EffectPluginConstants

/**
 * Completes rule names and severities inside `// @effect-diagnostics[-next-line] rule:severity`
 * directive comments. `@effect/tsgo` already provides "Disable <rule>" code actions; this covers the
 * hand-authoring path the LSP does not.
 */
class EffectDiagnosticDirectiveCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val extension = parameters.originalFile.virtualFile?.extension
                    if (extension !in EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS) {
                        return
                    }

                    val text = parameters.originalFile.text
                    val offset = parameters.offset.coerceIn(0, text.length)
                    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                    val linePrefix = text.substring(lineStart, offset)

                    val completion = completionFor(linePrefix) ?: return
                    val scoped = result.withPrefixMatcher(completion.prefix)
                    when (completion.kind) {
                        DirectiveCompletionKind.SEVERITY -> SEVERITIES.forEach { severity ->
                            scoped.addElement(LookupElementBuilder.create(severity).withTypeText("Effect severity"))
                        }

                        DirectiveCompletionKind.RULE -> RULE_NAMES.forEach { rule ->
                            scoped.addElement(LookupElementBuilder.create(rule).withTypeText("Effect rule"))
                        }
                    }
                }
            },
        )
    }

    enum class DirectiveCompletionKind { RULE, SEVERITY }

    data class DirectiveCompletion(val kind: DirectiveCompletionKind, val prefix: String)

    companion object {
        private const val DIRECTIVE_MARKER = "@effect-diagnostics"
        private const val RULE_TOKEN_CHARS = "-_*/."

        val SEVERITIES: List<String> = listOf("off", "warning", "error", "suggestion", "message", "skip-file")

        /**
         * Decides, from the text on the current line up to the caret, whether to complete a rule name
         * or a severity, and the token prefix to match. Returns null when the line is not an
         * `@effect-diagnostics` directive. Kept pure so it can be unit-tested without the platform.
         */
        fun completionFor(linePrefix: String): DirectiveCompletion? {
            if (!linePrefix.contains(DIRECTIVE_MARKER)) {
                return null
            }
            val token = linePrefix.takeLastWhile { it.isLetterOrDigit() || it in RULE_TOKEN_CHARS }
            val lastColon = linePrefix.lastIndexOf(':')
            val lastSeparator = maxOf(linePrefix.lastIndexOf(' '), linePrefix.lastIndexOf('\t'))
            val kind = if (lastColon > lastSeparator) DirectiveCompletionKind.SEVERITY else DirectiveCompletionKind.RULE
            return DirectiveCompletion(kind, token)
        }

        // Effect language-service rule names shipped by @effect/tsgo (internal/rules), for directive
        // authoring. Category/target labels (Correctness, Style, Number, …) are intentionally excluded.
        val RULE_NAMES: List<String> = listOf(
            "anyUnknownInErrorContext", "asyncFunction", "catchAllToMapError", "catchToIgnore",
            "catchToOrElseSucceed", "catchUnfailableEffect", "classSelfMismatch", "cryptoRandomUUID",
            "cryptoRandomUUIDInEffect", "deterministicKeys", "duplicatePackage", "effectDoNotation",
            "effectFnIife", "effectFnImplicitAny", "effectFnOpportunity", "effectGenUsesAdapter",
            "effectInFailure", "effectInVoidSuccess", "effectMapFlatten", "effectMapVoid",
            "effectSucceedWithVoid", "extendsNativeError", "flatMapToMap", "floatingEffect", "genericEffectServices",
            "globalConsole", "globalConsoleInEffect", "globalDate", "globalDateInEffect",
            "globalErrorInEffectCatch", "globalErrorInEffectFailure", "globalFetch", "globalFetchInEffect",
            "globalRandom", "globalRandomInEffect", "globalTimers", "globalTimersInEffect",
            "instanceOfSchema", "layerMergeAllWithDependencies", "lazyEffect", "lazyPromiseInEffectSync",
            "leakingRequirements", "missedPipeableOpportunity", "missingEffectContext", "missingEffectError",
            "missingEffectServiceDependency", "missingLayerContext", "missingReturnYieldStar",
            "missingStarInYieldEffectGen", "multipleCatchTag", "multipleEffectProvide", "nestedEffectGenYield",
            "newPromise", "newSchemaClass", "nodeBuiltinImport", "nonObjectEffectServiceType", "outdatedApi",
            "overriddenSchemaConstructor", "preferSchemaOverJson", "processEnv", "processEnvInEffect",
            "redundantMapError", "redundantOrDie", "redundantSchemaTagIdentifier", "returnEffectInGen",
            "runEffectInsideEffect", "schemaNumber", "schemaStructWithTag", "schemaSyncInEffect",
            "schemaUnionOfLiterals", "scopeInLayerEffect", "serviceNotAsClass", "strictBooleanExpressions",
            "strictEffectProvide", "tryCatchInEffectGen", "unknownInEffectCatch",
            "unnecessaryArrowBlock", "unnecessaryEffectGen", "unnecessaryFailYieldableError", "unnecessaryPipe",
            "unnecessaryPipeChain", "unnecessaryTypeofType", "unsafeEffectTypeAssertion",
        )
    }
}
