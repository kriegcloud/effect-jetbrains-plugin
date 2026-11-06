package dev.effect.intellij.debug

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.target.value.TargetValue
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.local.LocalTargetEnvironmentRequest
import com.intellij.javascript.nodejs.interpreter.NodeTargetRunSetup
import com.intellij.util.PathMapper
import java.lang.reflect.Proxy
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.nodejs.run.NodeJsRunConfigurationType
import dev.effect.intellij.settings.EffectProjectSettingsService
import java.nio.file.Files

class EffectNodeInstrumentationRunConfigurationExtensionTest : BasePlatformTestCase() {
    fun testDisabledAndNonmatchingProfilesHaveNoLaunchSession() {
        val configuration = NodeJsRunConfigurationType.getInstance().createTemplateConfiguration(project)
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val settings = EffectProjectSettingsService.getInstance(project)
        val extension = EffectNodeInstrumentationRunConfigurationExtension()
        settings.updateSettings(settings.currentSettings().copy(injectNodeOptions = false))
        assertNull(extension.createLaunchSession(configuration, environment))
        settings.updateSettings(settings.currentSettings().copy(injectNodeOptions = true, injectDebugConfigurationTypes = listOf("unrelated")))
        assertNull(extension.createLaunchSession(configuration, environment))
    }

    fun testRemoteTargetRequestsUploadAndUsesMappedPath() {
        val configuration = NodeJsRunConfigurationType.getInstance().createTemplateConfiguration(project)
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val settings = EffectProjectSettingsService.getInstance(project)
        settings.updateSettings(settings.currentSettings().copy(injectNodeOptions = true, injectDebugConfigurationTypes = listOf("*")))
        val script = Files.createTempDirectory("effect remote preload ").resolve("script with spaces.js")
        Files.writeString(script, "// preload")
        try {
            val extension = EffectNodeInstrumentationRunConfigurationExtension { script }
            val launch = extension.createLaunchSession(configuration, environment)!!
            // A non-local request exercises the platform upload branch without a live SSH host.
            val delegate = LocalTargetEnvironmentRequest()
            val request = Proxy.newProxyInstance(
                TargetEnvironmentRequest::class.java.classLoader,
                arrayOf(TargetEnvironmentRequest::class.java),
            ) { _, method, args -> method.invoke(delegate, *(args ?: emptyArray())) } as TargetEnvironmentRequest
            fun target(mapper: PathMapper?) = NodeTargetRun(
                NodeJsLocalInterpreter("/usr/bin/node"), project, null, NodeTargetRunOptions.of(false),
                object : NodeTargetRunSetup(request, mapper) {
                    override fun configureCommandLine(targetRun: NodeTargetRun) = Unit
                },
            )
            val uploadTarget = target(null)
            launch.addNodeOptionsTo(uploadTarget)
            assertTrue("Preload directory must be uploaded", request.uploadVolumes.any { it.localRootPath == script.parent })
            val remotePath = "/remote/cache/script with spaces.js"
            val mappedTarget = target(object : PathMapper {
                override fun canReplaceLocal(path: String): Boolean = true
                override fun canReplaceRemote(path: String): Boolean = true
                override fun convertToRemote(path: String): String = remotePath
                override fun convertToRemote(paths: MutableCollection<String>): MutableList<String> = paths.map { remotePath }.toMutableList()
                override fun isEmpty(): Boolean = false
                override fun convertToLocal(path: String): String = script.toString()
            })
            launch.addNodeOptionsTo(mappedTarget)
            val getter = NodeTargetRun::class.java.methods.single { it.name.startsWith("getNodeOptions$") }
            @Suppress("UNCHECKED_CAST")
            val options = getter.invoke(mappedTarget) as List<TargetValue<String>>
            assertEquals(listOf("--require", remotePath), options.map { it.targetValue.blockingGet(1000) })
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(script.parent)
        }
    }

    fun testMatchingProfilesInjectExactlyTwoIntactOptions() {
        val configuration = NodeJsRunConfigurationType.getInstance().createTemplateConfiguration(project)
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        val settings = EffectProjectSettingsService.getInstance(project)
        val script = Files.createTempDirectory("effect preload with spaces ").resolve("instrumentation script.js")
        Files.writeString(script, "// preload")
        try {
            val extension = EffectNodeInstrumentationRunConfigurationExtension { script }
            for (match in listOf(configuration.type.id, configuration.type.displayName, "*")) {
                settings.updateSettings(settings.currentSettings().copy(injectNodeOptions = true, injectDebugConfigurationTypes = listOf(" $match ")))
                val launch = extension.createLaunchSession(configuration, environment)!!
                val targetRun = NodeTargetRun(NodeJsLocalInterpreter("/usr/bin/node"), project, null, NodeTargetRunOptions.of(false))
                launch.addNodeOptionsTo(targetRun)
                // Platform exposes this list as a Kotlin-internal, module-mangled getter.
                val getter = NodeTargetRun::class.java.methods.single { it.name.startsWith("getNodeOptions$") }
                @Suppress("UNCHECKED_CAST")
                val options = getter.invoke(targetRun) as List<TargetValue<String>>
                assertEquals(2, options.size)
                assertEquals("--require", options[0].localValue.blockingGet(1000))
                assertEquals(script.toString(), options[1].localValue.blockingGet(1000))
            }
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(script.parent)
        }
    }
}
