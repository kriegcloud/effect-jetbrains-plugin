package dev.effect.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class EffectSettingsValidationTest : BasePlatformTestCase() {
    fun testPinnedModeRequiresVersion() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.PINNED,
                pinnedVersion = "",
            ),
        )

        assertTrue(problems.any { it.field == "pinnedVersion" })
    }

    fun testJsonValidationReportsErrors() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                initializationOptionsJson = "{not-json}",
                workspaceConfigurationJson = "{still-not-json}",
            ),
        )

        assertTrue(problems.any { it.field == "initializationOptionsJson" })
        assertTrue(problems.any { it.field == "workspaceConfigurationJson" })
    }

    fun testManualModeAcceptsExistingExecutable() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        val executable = Files.createTempFile("effect-manual", if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else "")
        executable.toFile().setExecutable(true, false)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = executable.toString(),
            ),
        )

        assertEmpty(problems)
    }
}
