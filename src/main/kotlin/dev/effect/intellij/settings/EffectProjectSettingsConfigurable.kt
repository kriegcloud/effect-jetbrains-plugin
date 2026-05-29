package dev.effect.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class EffectProjectSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private val settingsService = EffectProjectSettingsService.getInstance(project)
    private var component: EffectProjectSettingsComponent? = null

    override fun getId(): String = "dev.effect.intellij.settings"

    override fun getDisplayName(): String = "Effect"

    override fun createComponent(): JComponent {
        val ui = EffectProjectSettingsComponent(project)
        ui.reset(settingsService.currentSettings())
        component = ui
        return ui.panel
    }

    override fun isModified(): Boolean = component?.toSettings()?.let { it != settingsService.currentSettings() } ?: false

    override fun apply() {
        val ui = component ?: return
        val settings = ui.toSettings()
        val problems = settingsService.validate(settings).filter { it.severity == SettingSeverity.ERROR }
        if (problems.isNotEmpty()) {
            throw ConfigurationException(problems.joinToString(separator = "\n") { it.message })
        }
        settingsService.updateSettings(settings)
        ui.showProblems(settingsService.validate(settings))
    }

    override fun reset() {
        component?.reset(settingsService.currentSettings())
    }

    override fun disposeUIResources() {
        component = null
    }
}

private class EffectProjectSettingsComponent(private val project: Project) {
    private val binaryMode = ComboBox(EffectBinaryMode.entries.toTypedArray())
    private val pinnedVersion = JBTextField()
    private val manualBinaryPath = TextFieldWithBrowseButton()
    private val extraEnv = JBTextArea(8, 40)
    private val initializationOptions = JBTextArea(8, 40)
    private val workspaceConfiguration = JBTextArea(8, 40)
    private val devToolsPort = JSpinner(SpinnerNumberModel(34_437, 1, 65_535, 1))
    private val metricsPollInterval = JSpinner(SpinnerNumberModel(500, 50, 60_000, 50))
    private val injectNodeOptions = JCheckBox("Inject Effect instrumentation into Node.js run/debug configurations")
    private val injectDebugConfigurationTypes = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val spanStackIgnoreList = JBTextArea(4, 40)
    private val debuggerNotice = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = "Attach-time instrumentation is available for active debug sessions. Optional NODE_OPTIONS injection applies to Node.js run/debug configurations; live Context, Span Stack, Fibers, and Breakpoints snapshots are still best-effort while the JetBrains debugger bridge matures."
    }
    private val validationLabel = JBLabel()
    val panel: JComponent = JPanel(BorderLayout()).apply {
        val manualBinaryDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().apply {
            title = "Select @effect/tsgo binary"
            description = "Choose the native tsgo executable for manual mode."
        }
        manualBinaryPath.addBrowseFolderListener(TextBrowseFolderListener(manualBinaryDescriptor, project))

        val binaryPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Binary mode", binaryMode)
            .addLabeledComponent("Pinned version", pinnedVersion)
            .addLabeledComponent("Manual binary path", manualBinaryPath)
            .panel

        val lspPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Extra environment (KEY=VALUE per line)", JBScrollPane(extraEnv))
            .addLabeledComponent("Initialization options JSON", JBScrollPane(initializationOptions))
            .addLabeledComponent("Workspace configuration JSON", JBScrollPane(workspaceConfiguration))
            .panel

        val devToolsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Dev Tools port", devToolsPort)
            .addLabeledComponent("Metrics poll interval (ms)", metricsPollInterval)
            .panel

        val debugPanel = FormBuilder.createFormBuilder()
            .addComponent(injectNodeOptions)
            .addLabeledComponent("Run/debug configuration types", JBScrollPane(injectDebugConfigurationTypes))
            .addLabeledComponent("Span stack ignore list", JBScrollPane(spanStackIgnoreList))
            .addLabeledComponent("Debugger status", JBScrollPane(debuggerNotice))
            .panel

        val splitter = JBSplitter(true, 0.5f).apply {
            firstComponent = FormBuilder.createFormBuilder()
                .addComponentFillVertically(binaryPanel, 0)
                .addSeparator()
                .addComponentFillVertically(lspPanel, 0)
                .panel
            secondComponent = FormBuilder.createFormBuilder()
                .addComponentFillVertically(devToolsPanel, 0)
                .addSeparator()
                .addComponentFillVertically(debugPanel, 0)
                .panel
        }

        add(splitter, BorderLayout.CENTER)
        add(validationLabel, BorderLayout.SOUTH)
        preferredSize = Dimension(900, 640)

        binaryMode.addActionListener { updateBinaryFieldState() }
        listOf(extraEnv, initializationOptions, workspaceConfiguration, injectDebugConfigurationTypes, spanStackIgnoreList).forEach { area ->
            area.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    clearProblems()
                }
            })
        }
        updateBinaryFieldState()
        clearProblems()
    }

    fun reset(settings: EffectProjectSettings) {
        binaryMode.selectedItem = settings.binaryMode
        pinnedVersion.text = settings.pinnedVersion
        manualBinaryPath.text = settings.manualBinaryPath
        extraEnv.text = settings.extraEnv.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" }
        initializationOptions.text = settings.initializationOptionsJson
        workspaceConfiguration.text = settings.workspaceConfigurationJson
        devToolsPort.value = settings.devToolsPort
        metricsPollInterval.value = settings.metricsPollIntervalMs
        injectNodeOptions.isSelected = settings.injectNodeOptions
        injectDebugConfigurationTypes.text = settings.injectDebugConfigurationTypes.joinToString(separator = "\n")
        spanStackIgnoreList.text = settings.spanStackIgnoreList.joinToString(separator = "\n")
        clearProblems()
        updateBinaryFieldState()
    }

    fun toSettings(): EffectProjectSettings =
        EffectProjectSettings(
            binaryMode = binaryMode.selectedItem as? EffectBinaryMode ?: EffectBinaryMode.MANUAL,
            pinnedVersion = pinnedVersion.text.trim(),
            manualBinaryPath = manualBinaryPath.text.trim(),
            extraEnv = parseEnv(extraEnv.text),
            initializationOptionsJson = initializationOptions.text.trim(),
            workspaceConfigurationJson = workspaceConfiguration.text.trim(),
            devToolsPort = devToolsPort.value as Int,
            metricsPollIntervalMs = metricsPollInterval.value as Int,
            spanStackIgnoreList = parseList(spanStackIgnoreList.text),
            injectNodeOptions = injectNodeOptions.isSelected,
            injectDebugConfigurationTypes = parseList(injectDebugConfigurationTypes.text).ifEmpty { listOf("*") },
        )

    fun showProblems(problems: List<SettingProblem>) {
        if (problems.isEmpty()) {
            validationLabel.text = "Configuration looks good."
            return
        }
        validationLabel.text = problems.joinToString(separator = " | ") { it.message }
    }

    private fun clearProblems() {
        validationLabel.text = "All user-visible Effect features are project-scoped."
    }

    private fun updateBinaryFieldState() {
        val mode = binaryMode.selectedItem as? EffectBinaryMode ?: EffectBinaryMode.MANUAL
        pinnedVersion.isEnabled = mode == EffectBinaryMode.PINNED
        manualBinaryPath.isEnabled = mode == EffectBinaryMode.MANUAL
    }

    private fun parseEnv(text: String): Map<String, String> =
        text.lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .associate { line ->
                val parts = line.split("=", limit = 2)
                when (parts.size) {
                    1 -> parts[0].trim() to ""
                    else -> parts[0].trim() to parts[1]
                }
            }

    private fun parseList(text: String): List<String> =
        text.lineSequence()
            .flatMap { line -> line.splitToSequence(",") }
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
}
