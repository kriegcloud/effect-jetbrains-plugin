package dev.effect.intellij.settings

import com.intellij.lang.typescript.compiler.TypeScriptCompilerSettings
import com.intellij.lang.typescript.compiler.ui.TypeScriptConfigurable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.ex.Settings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.typescript.*
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import javax.swing.JCheckBox

class EffectTypeScriptSettingsDialogTest : BasePlatformTestCase() {
    fun testSettingsDialogApplyKeepsTsrsAndDefersRestart() {
        val settings = TypeScriptCompilerSettings.getSettings(project)
        val originalRecompile = settings.recompileOnChanges
        val originalDirectory = settings.typeScriptServiceDirectory
        val originalVersion = settings.versionType
        val originalUse = settings.useService
        val page = TypeScriptConfigurable(project)
        val pageComponent = page.createComponent()!!
        page.reset()
        fun checkboxes(component: java.awt.Component): List<JCheckBox> =
            (if (component is JCheckBox) listOf(component) else emptyList()) +
                (component as? java.awt.Container)?.components.orEmpty().flatMap(::checkboxes)
        val recompile = checkboxes(pageComponent).single { it.text.contains("Recompile", ignoreCase = true) }
        val group = object : ConfigurableGroup {
            override fun getDisplayName() = "Test"
            override fun getConfigurables(): Array<Configurable> = arrayOf(page)
        }
        val context = object : Settings(listOf(group)) {
            override fun selectImpl(configurable: Configurable): Promise<in Any> = resolvedPromise(Any())
            override fun getConfigurableWithInitializedUiComponentImpl(configurable: Configurable, withReset: Boolean) = configurable
            override fun checkModifiedImpl(configurable: Configurable) = Unit
            override fun setSearchText(text: String) = Unit
        }
        val manager = ActionManager.getInstance()
        val actionId = "TypeScript.Restart.Service"
        val previous = manager.getAction(actionId)
        if (previous != null) manager.unregisterAction(actionId)
        var restarts = 0
        manager.registerAction(actionId, object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                assertSame(project, e.project)
                restarts++
            }
        })
        val root = java.nio.file.Files.createTempDirectory("effect-settings-dialog").toRealPath()
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val install = java.nio.file.Files.createDirectories(root.resolve("install"))
        java.nio.file.Files.writeString(install.resolve("tsrs"), "binary")
        java.nio.file.Files.writeString(install.resolve("lib.d.ts"), "lib")
        val candidate = TsrsCandidate(install.resolve("tsrs"), "7.1.0", "0.30.0-ts7.1.0", TsrsSource.WORKSPACE_TSRS_PACKAGE, false)
        val directory = EffectTsrsTypeScriptPackage.packageDirectory(root.resolve("generated"), candidate)
        var done = false
        val component = EffectProjectSettingsComponent(project, discoverVersions = { emptyList() },
            discoverTypeScript = { EffectWebStormTypeScriptService.CurrentState(null, false, emptyList()) },
            switchTypeScript = { before, callback ->
                EffectWebStormTypeScriptService(project).switchToTsrs(
                    preparePackage = { candidate to EffectTsrsTypeScriptPackage.prepare(root.resolve("generated"), candidate, TsrsPlatform.current()!!) }, reportResult = {}, beforeApply = before,
                    onDone = { callback(it); assertTrue(it.isSuccess); done = true })
            })
        val dataManager = com.intellij.ide.DataManager.getInstance() as com.intellij.ide.impl.HeadlessDataManager
        dataManager.setTestDataProvider({ id -> if (Settings.KEY.`is`(id)) context else null }, testRootDisposable)
        assertSame(context, Settings.KEY.getData(com.intellij.ide.DataManager.getInstance().getDataContext(component.panel)))
        assertSame(page, context.find(EffectWebStormTypeScriptService.TYPESCRIPT_CONFIGURABLE_ID))
        val modal = Any()
        try {
            LaterInvocator.enterModal(modal)
            recompile.isSelected = !originalRecompile
            assertTrue(page.isModified)
            val activate = component.javaClass.getDeclaredMethod("useTsrsForWebStormTypeScript")
            activate.isAccessible = true
            activate.invoke(component)
            PlatformTestUtil.waitWithEventsDispatching("Settings activation", { done }, 20)
            assertEquals(0, restarts)
            page.apply()
            assertEquals(directory.toString(), settings.typeScriptServiceDirectory)
            assertEquals(!originalRecompile, settings.recompileOnChanges)
            LaterInvocator.leaveModal(modal)
            PlatformTestUtil.waitWithEventsDispatching("Deferred restart", { restarts == 1 }, 20)
        } finally {
            if (LaterInvocator.getCurrentModalEntities().contains(modal)) LaterInvocator.leaveModal(modal)
            component.dispose()
            com.intellij.openapi.util.io.FileUtil.delete(root)
            page.disposeUIResources()
            manager.unregisterAction(actionId)
            if (previous != null) manager.registerAction(actionId, previous)
            settings.recompileOnChanges = originalRecompile
            settings.typeScriptServiceDirectory = originalDirectory
            settings.versionType = originalVersion
            settings.useService = originalUse
        }
    }
}
