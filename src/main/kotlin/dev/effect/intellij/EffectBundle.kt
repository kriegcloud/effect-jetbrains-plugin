package dev.effect.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.EffectBundle"

object EffectBundle {
    private val dynamicBundle = DynamicBundle(EffectBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        dynamicBundle.getMessage(key, *params)
}
