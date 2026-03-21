package dev.effect.intellij.webview

import com.intellij.ui.jcef.JBCefApp

object EffectWebTracerSupport {
    // Optional advanced tracer work stays capability-gated until a real browser surface is needed.
    fun isSupported(): Boolean = JBCefApp.isSupported()
}
