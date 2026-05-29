package dev.effect.intellij.webview

import com.intellij.openapi.Disposable
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import dev.effect.intellij.devtools.DevToolsRuntimeState
import dev.effect.intellij.devtools.RuntimeClientSnapshot
import dev.effect.intellij.devtools.RuntimeSpanEventSnapshot
import dev.effect.intellij.devtools.RuntimeSpanSnapshot
import javax.swing.JComponent

object EffectWebTracerSupport {
    fun isSupported(): Boolean = JBCefApp.isSupported()

    fun createPanelOrNull(): EffectWebTracerPanel? =
        if (isSupported()) EffectWebTracerPanel() else null
}

class EffectWebTracerPanel : Disposable {
    private val browser = JBCefBrowser()
    val component: JComponent = browser.component

    fun refresh(state: DevToolsRuntimeState) {
        browser.loadHTML(renderTracerHtml(state))
    }

    override fun dispose() {
        browser.dispose()
    }
}

private fun renderTracerHtml(state: DevToolsRuntimeState): String {
    val activeClient = state.clients.firstOrNull { it.id == state.activeClientId }
    val body = when {
        !state.running -> "<section class=\"empty\">Runtime server is stopped.</section>"
        activeClient == null -> "<section class=\"empty\">No active runtime client selected.</section>"
        activeClient.rootSpans.isEmpty() -> "<section class=\"empty\">No span activity published yet for ${escapeHtml(activeClient.name)}.</section>"
        else -> renderClient(activeClient)
    }

    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <style>
            :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
            body { margin: 0; padding: 16px; background: Canvas; color: CanvasText; }
            h1 { font-size: 16px; margin: 0 0 12px; }
            .empty { color: GrayText; padding: 18px 0; }
            .span { border-left: 2px solid #3574f0; margin: 8px 0 8px 12px; padding: 6px 0 6px 12px; }
            .span header { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
            .name { font-weight: 650; }
            .meta, .event { color: GrayText; font-size: 12px; }
            .event { margin: 4px 0 0 12px; }
            dl { display: grid; grid-template-columns: max-content 1fr; gap: 3px 10px; margin: 6px 0 0; font-size: 12px; }
            dt { color: GrayText; }
            dd { margin: 0; overflow-wrap: anywhere; }
          </style>
        </head>
        <body>
          $body
        </body>
        </html>
    """.trimIndent()
}

private fun renderClient(client: RuntimeClientSnapshot): String =
    buildString {
        append("<h1>")
        append(escapeHtml(client.name))
        append("</h1>")
        client.rootSpans.forEach { span -> append(renderSpan(span)) }
    }

private fun renderSpan(span: RuntimeSpanSnapshot): String =
    buildString {
        append("<article class=\"span\"><header><span class=\"name\">")
        append(escapeHtml(span.name.ifBlank { span.spanId }))
        append("</span><span class=\"meta\">")
        append(escapeHtml(span.status))
        append("</span></header><dl>")
        appendDetail("spanId", span.spanId)
        appendDetail("traceId", span.traceId)
        appendDetail("sampled", span.sampled.toString())
        span.details.forEach { appendDetail(it.key, it.value) }
        append("</dl>")
        span.events.forEach { append(renderEvent(it)) }
        span.children.forEach { append(renderSpan(it)) }
        append("</article>")
    }

private fun renderEvent(event: RuntimeSpanEventSnapshot): String =
    buildString {
        append("<div class=\"event\">")
        append(escapeHtml(event.name))
        append(" @ ")
        append(escapeHtml(event.startTime))
        append("</div>")
    }

private fun StringBuilder.appendDetail(key: String, value: String) {
    append("<dt>")
    append(escapeHtml(key))
    append("</dt><dd>")
    append(escapeHtml(value))
    append("</dd>")
}

private fun escapeHtml(value: String): String =
    buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
