package dev.effect.intellij.debug

data class EffectSourceLocation(
    val path: String,
    val line: Int = 0,
    val column: Int = 0,
)

data class EffectDebugTreeEntry(
    val title: String,
    val detail: String = title,
    val location: EffectSourceLocation? = null,
    val fiberId: String? = null,
    val children: List<EffectDebugTreeEntry> = emptyList(),
) {
    val copyText: String
        get() = detail.ifBlank { title }

    override fun toString(): String = title
}

object EffectDebugTreeModels {
    fun contextEntries(state: DebugBridgeState): List<EffectDebugTreeEntry> {
        val snapshot = state.snapshot ?: return listOf(messageEntry(state.guidance))
        if (snapshot.context.isEmpty()) {
            return listOf(messageEntry(snapshot.message ?: "No Effect Context entries are visible for the current fiber."))
        }
        return buildList {
            snapshot.message?.let { add(messageEntry(it)) }
            snapshot.context.forEach { entry ->
                add(
                    EffectDebugTreeEntry(
                        title = entry.tag,
                        detail = entry.value.formatDebugValue(),
                        children = entry.value.children.map(::valueEntry),
                    ),
                )
            }
        }
    }

    fun spanStackEntries(
        state: DebugBridgeState,
        ignoreList: List<String>,
        ignoreEnabled: Boolean,
    ): List<EffectDebugTreeEntry> {
        val snapshot = state.snapshot ?: return listOf(messageEntry(state.guidance))
        val spans = snapshot.spanStack.filterNot { span ->
            ignoreEnabled && ignoreList.any { pattern -> wildcardMatches(span.name, pattern) }
        }
        if (spans.isEmpty()) {
            val fallback = if (snapshot.spanStack.isEmpty()) {
                snapshot.message ?: "No Effect spans are visible for the current fiber."
            } else {
                "All visible Effect spans are hidden by the span-stack ignore list."
            }
            return listOf(messageEntry(fallback))
        }
        return buildList {
            snapshot.message?.let { add(messageEntry(it)) }
            spans.forEach { add(spanEntry(it)) }
        }
    }

    fun fiberEntries(state: DebugBridgeState): List<EffectDebugTreeEntry> {
        val snapshot = state.snapshot ?: return listOf(messageEntry(state.guidance))
        if (snapshot.fibers.isEmpty()) {
            return listOf(messageEntry(snapshot.message ?: "No live Effect fibers have been observed yet."))
        }
        return buildList {
            snapshot.message?.let { add(messageEntry(it)) }
            snapshot.fibers.forEach { fiber ->
                add(
                    EffectDebugTreeEntry(
                        title = fiber.fiberTitle(),
                        detail = fiber.formatDebugFiber(),
                        fiberId = fiber.id.takeIf { fiber.isInterruptible && !fiber.isInterrupted },
                        children = buildList {
                            fiber.stack.forEach { add(spanEntry(it)) }
                            fiber.children.forEach { child -> add(EffectDebugTreeEntry("child: $child")) }
                        },
                    ),
                )
            }
        }
    }

    fun breakpointEntries(state: DebugBridgeState): List<EffectDebugTreeEntry> {
        val snapshot = state.snapshot ?: return listOf(messageEntry(state.guidance))
        val breakpoints = snapshot.breakpoints
        return buildList {
            add(
                EffectDebugTreeEntry(
                    title = if (breakpoints.pauseOnDefects) "Pause on defects: enabled" else "Pause on defects: disabled",
                    detail = breakpoints.formatDebugBreakpoints(),
                ),
            )
            breakpoints.location?.toSourceLocation()?.let { location ->
                add(
                    EffectDebugTreeEntry(
                        title = "Last pause location",
                        detail = "${location.path}:${location.line}:${location.column}",
                        location = location,
                    ),
                )
            }
            breakpoints.values.forEach { value -> add(valueEntry(value)) }
        }
    }

    private fun messageEntry(message: String): EffectDebugTreeEntry =
        EffectDebugTreeEntry(title = message, detail = message)

    private fun spanEntry(span: EffectDebugSpanEntry): EffectDebugTreeEntry =
        EffectDebugTreeEntry(
            title = span.name,
            detail = span.formatDebugSpan(),
            location = span.toSourceLocation(),
            children = span.attributes.map { attribute -> EffectDebugTreeEntry(title = attribute, detail = attribute) },
        )

    private fun valueEntry(value: EffectDebugValueSnapshot): EffectDebugTreeEntry =
        EffectDebugTreeEntry(
            title = value.valueTitle(),
            detail = value.formatDebugValue(),
            children = value.children.map(::valueEntry),
        )
}

fun EffectDebugValueSnapshot.formatDebugValue(indent: String = ""): String = buildString {
    append(indent)
    append(label)
    type?.takeIf { it != label }?.let { append(" : ").append(it) }
    summary?.takeIf { it != label }?.let { append(" = ").append(it) }
    if (children.isNotEmpty()) {
        children.forEach { child ->
            appendLine()
            append(child.formatDebugValue("$indent  "))
        }
    }
}

fun EffectDebugSpanEntry.formatDebugSpan(): String = buildString {
    appendLine(name)
    spanId?.let { appendLine("spanId: $it") }
    traceId?.let { appendLine("traceId: $it") }
    stackIndex?.let { appendLine("stackIndex: $it") }
    if (path != null) {
        appendLine("source: $path:${line ?: 0}:${column ?: 0}")
    }
    if (attributes.isNotEmpty()) {
        appendLine("attributes: ${attributes.joinToString()}")
    }
}

fun EffectDebugFiberEntry.formatDebugFiber(): String = buildString {
    append(fiberTitle())
    appendLine()
    appendLine("interruptible: $isInterruptible")
    appendLine("interrupted: $isInterrupted")
    lifeTimeMillis?.let { appendLine("lifetimeMs: $it") }
    if (children.isNotEmpty()) {
        appendLine("children: ${children.joinToString()}")
    }
    if (stack.isNotEmpty()) {
        appendLine()
        appendLine("Span Stack")
        stack.forEach { span -> appendLine("  ${span.formatDebugSpan().replace("\n", "\n  ")}") }
    }
}

fun EffectDebugBreakpointsSnapshot.formatDebugBreakpoints(): String = buildString {
    appendLine("pauseOnDefects: $pauseOnDefects")
    location?.let {
        appendLine("lastPauseLocation: ${it.path ?: "<unknown>"}:${it.line ?: 0}:${it.column ?: 0}")
    }
    if (values.isNotEmpty()) {
        appendLine()
        appendLine("Values")
        values.forEach { value -> appendLine(value.formatDebugValue("  ")) }
    }
}

private fun EffectDebugFiberEntry.fiberTitle(): String = buildString {
    append("Fiber #")
    append(id)
    if (isCurrent) append(" current")
    if (isInterrupted) append(" interrupted")
    if (!isInterruptible) append(" uninterruptible")
}

private fun EffectDebugValueSnapshot.valueTitle(): String =
    buildString {
        append(label)
        summary?.takeIf { it != label }?.let { append(" = ").append(it) }
    }

private fun EffectDebugSpanEntry.toSourceLocation(): EffectSourceLocation? {
    val sourcePath = path?.takeIf(String::isNotBlank) ?: return null
    return EffectSourceLocation(sourcePath, line ?: 0, column ?: 0)
}

private fun EffectDebugBreakpointLocation.toSourceLocation(): EffectSourceLocation? {
    val sourcePath = path?.takeIf(String::isNotBlank) ?: return null
    return EffectSourceLocation(sourcePath, line ?: 0, column ?: 0)
}

private fun wildcardMatches(value: String, pattern: String): Boolean {
    val regex = buildString {
        append('^')
        pattern.forEach { char ->
            when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(char.toString()))
            }
        }
        append('$')
    }.toRegex()
    return regex.matches(value)
}
