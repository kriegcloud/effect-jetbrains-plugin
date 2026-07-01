package dev.effect.intellij.editor

import dev.effect.intellij.editor.EffectLineMarkerProvider.Companion.matchEffectConstruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EffectLineMarkerMatcherTest {
    @Test
    fun matchesEffectConstructs() {
        assertNotNull(matchEffectConstruct("Effect.Service"))
        assertEquals("Effect generator", matchEffectConstruct("Effect.gen"))
        assertEquals("Effect function", matchEffectConstruct("Effect.fn"))
        assertEquals("Layer construction", matchEffectConstruct("Layer.effect"))
        assertEquals("Layer construction", matchEffectConstruct("Layer.mergeAll"))
        assertNotNull(matchEffectConstruct("Schema.Class"))
        assertNotNull(matchEffectConstruct("Schema.TaggedError"))
        assertEquals("Effect dependency provision", matchEffectConstruct("Effect.provide"))
        assertEquals("Effect dependency provision", matchEffectConstruct("Layer.provideMerge"))
    }

    @Test
    fun ignoresUnrelatedReferences() {
        assertNull(matchEffectConstruct("console.log"))
        assertNull(matchEffectConstruct("Array.gen"))
        assertNull(matchEffectConstruct("Effect.succeed")) // only Layer.succeed is marked
        assertNull(matchEffectConstruct("Schema.Struct"))
        assertNull(matchEffectConstruct(""))
    }
}
