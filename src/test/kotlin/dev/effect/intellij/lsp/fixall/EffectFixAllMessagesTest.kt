package dev.effect.intellij.lsp.fixall

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.EffectBundle

/** Guards the MessageFormat patterns (doubled quotes, choice plurals) of the fix-all notifications. */
class EffectFixAllMessagesTest : BasePlatformTestCase() {
    fun testNotificationPatternsFormat() {
        assertEquals(
            "Fix all 'Replace with Effect.map' problems in file",
            EffectBundle.message("intention.fix.all.in.file.text", "Replace with Effect.map"),
        )
        assertEquals("Fixing all 'X' problems in file\u2026", EffectBundle.message("fix.all.in.file.progress", "X"))
        assertEquals("Applied 'X' to 1 problem", EffectBundle.message("fix.all.in.file.applied", "X", 1))
        assertEquals("Applied 'X' to 3 problems", EffectBundle.message("fix.all.in.file.applied", "X", 3))
        assertEquals(
            "Applied 'X' to 2 of 5 problems. Skipped 1 problem whose edits overlapped an applied fix (run \"Fix all\" again for them), " +
                "0 problems without a matching fix, and 2 problems whose edits could not be applied to this file.",
            EffectBundle.message("fix.all.in.file.partial", "X", 2, 5, 1, 0, 2),
        )
        assertEquals("The 1 problem in this file no longer offers the 'X' fix.", EffectBundle.message("fix.all.in.file.no.matching.fix", "X", 1))
        assertEquals("None of the 4 problems in this file offer the 'X' fix.", EffectBundle.message("fix.all.in.file.no.matching.fix", "X", 4))
        assertTrue(EffectBundle.message("fix.all.in.file.not.applicable", "X", 1).startsWith("The 'X' fix was found for the 1 problem in this file"))
        assertTrue(EffectBundle.message("fix.all.in.file.not.applicable", "X", 3).startsWith("The 'X' fix was found for all 3 problems in this file"))
        assertEquals(
            "Could not apply 'X' to any problem: 2 problems without a matching fix, 1 problem whose edits could not be applied to this file, and 0 problems whose edits overlapped.",
            EffectBundle.message("fix.all.in.file.nothing.applied", "X", 2, 1, 0),
        )
        assertEquals("No 'rule' problems are reported in this file right now", EffectBundle.message("fix.all.in.file.nothing.to.fix", "rule"))
        assertEquals(
            "The Effect language server did not answer the textDocument/codeAction request (timed out, failed, or stopped).",
            EffectBundle.message("fix.all.in.file.no.response", "textDocument/codeAction"),
        )
    }
}
