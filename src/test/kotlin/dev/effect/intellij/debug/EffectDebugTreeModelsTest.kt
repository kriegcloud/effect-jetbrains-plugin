package dev.effect.intellij.debug

import junit.framework.TestCase

class EffectDebugTreeModelsTest : TestCase() {
    fun testLocalSourcePaths() {
        assertEquals("/tmp/app.ts", localDebugSourcePath("file:///tmp/app.ts"))
        assertEquals("/tmp/my app+test.ts", localDebugSourcePath("file:///tmp/my%20app+test.ts"))
        assertEquals("C:/my app.ts", localDebugSourcePath("file:///C:/my%20app.ts"))
        assertEquals("C:\\app.ts", localDebugSourcePath("C:\\app.ts"))
        assertEquals("/tmp/app.ts", localDebugSourcePath("/tmp/app.ts"))
        assertEquals("/tmp/app.ts", localDebugSourcePath("file://localhost/tmp/app.ts"))
        assertNull(localDebugSourcePath("file://remote/tmp/app.ts"))
        assertNull(localDebugSourcePath("file:///tmp/%zz.ts"))
        assertNull(localDebugSourcePath("https://example.com/app.ts"))
    }

    fun testTreeRetainsNamesAndNormalizesLocations() {
        val snapshot = EffectDebugSnapshot.parse("""{
          "context":[{"tag":"Service","value":{"label":"object","children":[
            {"label":"count","summary":"42"},
            {"label":"nested","children":[{"label":"ok","summary":"true"}]}
          ]}}],
          "spanStack":[{"name":"work","path":"file:///tmp/my%20app.ts","line":9,"column":19}],
          "breakpoints":{"location":{"path":"file:///tmp/my%20app.ts","line":9,"column":19}}
        }""")
        val state = DebugBridgeState(snapshot = snapshot)
        val children = EffectDebugTreeModels.contextEntries(state).single().children
        assertEquals("count = 42", children[0].title)
        assertEquals("nested", children[1].title)
        assertEquals("ok = true", children[1].children.single().title)
        val expected = EffectSourceLocation("/tmp/my app.ts", 9, 19)
        assertEquals(expected, EffectDebugTreeModels.spanStackEntries(state, emptyList(), false).single().location)
        assertEquals(expected, EffectDebugTreeModels.breakpointEntries(state)[1].location)
    }
}
