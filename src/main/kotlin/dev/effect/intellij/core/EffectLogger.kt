package dev.effect.intellij.core

import com.intellij.openapi.diagnostic.Logger

inline fun <reified T : Any> logger(): Logger = Logger.getInstance(T::class.java)
