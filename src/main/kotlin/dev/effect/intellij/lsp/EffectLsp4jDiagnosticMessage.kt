package dev.effect.intellij.lsp

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/**
 * Compatibility accessor for `Diagnostic.message` across the lsp4j versions bundled by the
 * supported platform lines.
 *
 * IntelliJ Platform 262.x (and 263 EAP builds up to 263.3889) bundle lsp4j 0.24.0, where
 * `Diagnostic.getMessage()` returns `String`. Builds from 263.4732 bundle lsp4j 1.0.0, where the
 * same getter returns `Either<String, MarkupContent>` (LSP 3.18 markup diagnostics). The plugin
 * compiles against the 262 stable platform, so a direct call binds the 0.24 descriptor and fails
 * on the newer line with `NoSuchMethodError: 'java.lang.String org.eclipse.lsp4j.Diagnostic.getMessage()'`.
 *
 * The getter and setters are therefore resolved reflectively against the running IDE, and the
 * value is normalized to plain text for rule-name matching while being copied verbatim.
 */
internal object EffectLsp4jDiagnosticMessage {
    private val lookup = MethodHandles.publicLookup()

    private val getter: MethodHandle? = runCatching {
        lookup.unreflect(Diagnostic::class.java.getMethod("getMessage"))
    }.getOrNull()

    /** Every single-argument `setMessage` overload the running lsp4j exposes, keyed by parameter type. */
    private val setters: List<Pair<Class<*>, MethodHandle>> = Diagnostic::class.java.methods
        .filter { it.name == "setMessage" && it.parameterCount == 1 }
        .mapNotNull { method -> runCatching { method.parameterTypes[0] to lookup.unreflect(method) }.getOrNull() }

    /** The message exactly as the running lsp4j stores it: `String`, `Either`, or `null`. */
    fun raw(diagnostic: Diagnostic): Any? =
        getter?.let { handle -> runCatching { handle.invokeWithArguments(diagnostic) }.getOrNull() }

    /** The message as plain text, whichever lsp4j shape holds it. */
    fun text(diagnostic: Diagnostic): String? = textOf(raw(diagnostic))

    internal fun textOf(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw
        is MarkupContent -> raw.value
        is Either<*, *> -> if (raw.isLeft) textOf(raw.left) else textOf(raw.right)
        else -> raw.toString()
    }

    /** Copies the message from [source] to [target] without changing its shape when the running lsp4j allows it. */
    fun copy(source: Diagnostic, target: Diagnostic) {
        val value = raw(source) ?: return
        val exact = setters.firstOrNull { (type, _) -> type.isInstance(value) }
        if (exact != null) {
            runCatching { exact.second.invokeWithArguments(target, value) }.onSuccess { return }
        }
        val text = textOf(value) ?: return
        setters.firstOrNull { (type, _) -> type == String::class.java }
            ?.let { (_, handle) -> runCatching { handle.invokeWithArguments(target, text) } }
    }
}
