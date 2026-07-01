package dev.effect.intellij.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement

/**
 * Adds a gutter icon next to common Effect constructs (Layer builders, `Effect.Service`,
 * `Schema.Class`/`TaggedClass`, `Effect.gen`/`Effect.fn`) so they stand out and are easy to scan.
 *
 * Detection is text/structure based (it matches a `Namespace.member` reference expression) to avoid a
 * hard dependency on JavaScript PSI type resolution; [matchEffectConstruct] is pure and unit-tested.
 */
class EffectLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Run only on leaf tokens so each construct is marked exactly once.
        if (element.firstChild != null) {
            return null
        }
        val member = element.text
        if (member !in ANCHOR_MEMBERS) {
            return null
        }
        val qualifiedText = element.parent?.text ?: return null
        val label = matchEffectConstruct(qualifiedText) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Toolwindows.ToolWindowMessages,
            { label },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { label },
        )
    }

    companion object {
        // Member identifiers that can anchor a marker; the qualifier is checked in matchEffectConstruct.
        val ANCHOR_MEMBERS: Set<String> = setOf(
            "Service", "Class", "TaggedClass", "TaggedError", "TaggedRequest",
            "gen", "fn", "effect", "scoped", "succeed", "mergeAll", "provide", "provideMerge",
        )

        private val CONSTRUCTS: Map<Regex, String> = linkedMapOf(
            Regex("""^Effect\.Service$""") to "Effect service definition",
            Regex("""^Effect\.gen$""") to "Effect generator",
            Regex("""^Effect\.fn$""") to "Effect function",
            Regex("""^(Effect|Layer)\.provide(Merge)?$""") to "Effect dependency provision",
            Regex("""^Layer\.(effect|scoped|succeed|mergeAll)$""") to "Layer construction",
            Regex("""^Schema\.(Class|TaggedClass|TaggedError|TaggedRequest)$""") to "Effect Schema class",
        )

        /** Returns a human-readable label for a `Namespace.member` reference, or null if not an Effect construct. */
        fun matchEffectConstruct(qualifiedText: String): String? {
            val normalized = qualifiedText.trim()
            return CONSTRUCTS.entries.firstOrNull { it.key.matches(normalized) }?.value
        }
    }
}
