package dev.effect.intellij.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel as uiPanel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.JBUI
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JSpinner
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

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
    private val booleanOptions = arrayOf(SERVER_DEFAULT_OPTION, "true", "false")
    private val binaryMode = ComboBox(EffectBinaryMode.entries.toTypedArray())
    private val pinnedVersion = ComboBox<PinnedVersionItem>().apply {
        isEditable = true
        renderer = SimpleListCellRenderer.create { label, item, _ ->
            label.text = item?.label.orEmpty()
        }
    }
    private val manualBinaryPath = TextFieldWithBrowseButton()
    private val extraEnv = JBTextArea(4, 40)
    private val initializationOptions = JBTextArea(4, 40)
    private val workspaceConfiguration = JBTextArea(4, 40)
    private val lspInlays = ComboBox(booleanOptions)
    private val lspMermaidProvider = JBTextField()
    private val lspNoExternal = ComboBox(booleanOptions)
    private val lspLayerGraphFollowDepth = JBTextField()
    private val lspDiagnosticSeverity = JBTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val devToolsPort = JSpinner(SpinnerNumberModel(34_437, 1, 65_535, 1))
    private val metricsPollInterval = JSpinner(SpinnerNumberModel(500, 50, 60_000, 50))
    private val injectNodeOptions = JCheckBox("Inject Effect instrumentation into Node.js run/debug configurations")
    private val injectDebugConfigurationTypes = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val spanStackIgnoreList = JBTextArea(4, 40)
    private val validationLabel = JBLabel()

    private lateinit var pinnedVersionRow: Row
    private lateinit var manualBinaryPathRow: Row

    val panel: JComponent = createPanel()

    init {
        val manualBinaryDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().apply {
            title = "Select @effect/tsgo binary"
            description = "Choose the native tsgo executable for manual mode."
        }
        manualBinaryPath.addBrowseFolderListener(TextBrowseFolderListener(manualBinaryDescriptor, project))
        pinnedVersion.addActionListener {
            if (pinnedVersion.selectedItem === PinnedVersionItem.SELECT) {
                choosePinnedVersionFromPackage()
            }
        }

        binaryMode.addActionListener { updateBinaryFieldState() }
        val clearProblemsListener = object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                clearProblems()
            }
        }
        listOf<JTextComponent>(
            extraEnv,
            initializationOptions,
            workspaceConfiguration,
            lspDiagnosticSeverity,
            injectDebugConfigurationTypes,
            spanStackIgnoreList,
            lspMermaidProvider,
            lspLayerGraphFollowDepth,
        ).forEach { field -> field.document.addDocumentListener(clearProblemsListener) }
        updateBinaryFieldState()
        clearProblems()
    }

    private fun createPanel(): JComponent {
        val content = uiPanel {
            group("Binary") {
                row("Binary mode") {
                    cell(binaryMode)
                }
                pinnedVersionRow = row("Pinned version") {
                    cell(pinnedVersion)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
                manualBinaryPathRow = row("Manual binary path") {
                    cell(manualBinaryPath)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
            }

            group("Language Server") {
                row("Inlays") {
                    cell(lspInlays)
                }
                row("Mermaid provider") {
                    cell(lspMermaidProvider)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
                row("Suppress external Mermaid links") {
                    cell(lspNoExternal)
                }
                row("Layer graph follow depth") {
                    cell(lspLayerGraphFollowDepth)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
                row {
                    button("Sync to tsconfig.json") { syncCurrentSettings() }
                        .comment(
                            "Current @effect/tsgo builds read these typed language-service options from " +
                                "tsconfig.json > compilerOptions.plugins > @effect/language-service. Sync writes " +
                                "the visible form values without applying the rest of this Settings page.",
                        )
                }
                collapsibleGroup("Advanced language-server settings", false) {
                    row("Extra environment (KEY=VALUE per line)") {
                        scrollCell(extraEnv)
                            .rows(4)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                    row("Initialization options JSON") {
                        scrollCell(initializationOptions)
                            .rows(4)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                    row("Diagnostic severity (rule=severity per line)") {
                        scrollCell(lspDiagnosticSeverity)
                            .rows(5)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                    row("Workspace configuration JSON") {
                        scrollCell(workspaceConfiguration)
                            .rows(4)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                }
            }

            group("Dev Tools") {
                row("Dev Tools port") {
                    cell(devToolsPort)
                }
                row("Metrics poll interval (ms)") {
                    cell(metricsPollInterval)
                }
            }

            group("Debugger") {
                row {
                    cell(injectNodeOptions)
                        .comment(DEBUGGER_NOTICE)
                }
                collapsibleGroup("Advanced debugger settings", false) {
                    row("Run/debug configuration types") {
                        scrollCell(injectDebugConfigurationTypes)
                            .rows(3)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                    row("Span stack ignore list") {
                        scrollCell(spanStackIgnoreList)
                            .rows(4)
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                }
            }

            row {
                cell(validationLabel)
                    .align(AlignX.FILL)
                    .resizableColumn()
            }
        }

        return JBScrollPane(
            content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            viewport.background = content.background
        }
    }

    fun reset(settings: EffectProjectSettings) {
        binaryMode.selectedItem = settings.binaryMode
        refreshDetectedVersions()
        setPinnedVersionText(settings.pinnedVersion)
        manualBinaryPath.text = settings.manualBinaryPath
        extraEnv.text = settings.extraEnv.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" }
        initializationOptions.text = settings.initializationOptionsJson
        workspaceConfiguration.text = settings.workspaceConfigurationJson
        lspInlays.selectedItem = settings.lspInlays?.toString() ?: SERVER_DEFAULT_OPTION
        lspMermaidProvider.text = settings.lspMermaidProvider
        lspNoExternal.selectedItem = settings.lspNoExternal?.toString() ?: SERVER_DEFAULT_OPTION
        lspLayerGraphFollowDepth.text = settings.lspLayerGraphFollowDepth?.toString().orEmpty()
        lspDiagnosticSeverity.text = settings.lspDiagnosticSeverity.entries.joinToString(separator = "\n") { (rule, severity) -> "$rule=$severity" }
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
            pinnedVersion = pinnedVersionText(),
            manualBinaryPath = manualBinaryPath.text.trim(),
            extraEnv = parseEnv(extraEnv.text),
            initializationOptionsJson = initializationOptions.text.trim(),
            workspaceConfigurationJson = workspaceConfiguration.text.trim(),
            lspInlays = parseOptionalBoolean(lspInlays.selectedItem),
            lspMermaidProvider = lspMermaidProvider.text.trim(),
            lspNoExternal = parseOptionalBoolean(lspNoExternal.selectedItem),
            lspLayerGraphFollowDepth = parseOptionalNonNegativeInt(lspLayerGraphFollowDepth.text),
            lspDiagnosticSeverity = parseEnv(lspDiagnosticSeverity.text),
            devToolsPort = devToolsPort.value as Int,
            metricsPollIntervalMs = metricsPollInterval.value as Int,
            spanStackIgnoreList = parseList(spanStackIgnoreList.text),
            injectNodeOptions = injectNodeOptions.isSelected,
            injectDebugConfigurationTypes = parseList(injectDebugConfigurationTypes.text)
                .ifEmpty { DEFAULT_NODE_DEBUG_CONFIGURATION_TYPES },
        )

    fun showProblems(problems: List<SettingProblem>) {
        if (problems.isEmpty()) {
            validationLabel.text = "Configuration looks good."
            return
        }
        validationLabel.text = problems.joinToString(separator = " | ") { it.message }
    }

    private fun syncCurrentSettings() {
        val settings = toSettings()
        val problems = EffectProjectSettingsService.getInstance(project).validate(settings)
        showProblems(problems)
        if (problems.any { it.severity == SettingSeverity.ERROR }) {
            return
        }
        EffectTsconfigSyncService.getInstance(project).sync(settings)
    }

    private fun clearProblems() {
        validationLabel.text = "All user-visible Effect features are project-scoped."
    }

    private fun updateBinaryFieldState() {
        val mode = binaryMode.selectedItem as? EffectBinaryMode ?: EffectBinaryMode.MANUAL
        pinnedVersionRow.visible(mode == EffectBinaryMode.PINNED)
        manualBinaryPathRow.visible(mode == EffectBinaryMode.MANUAL)
        pinnedVersion.isEnabled = mode == EffectBinaryMode.PINNED
        manualBinaryPath.isEnabled = mode == EffectBinaryMode.MANUAL
        if (mode == EffectBinaryMode.PINNED && pinnedVersionText().isBlank()) {
            // Switching to PINNED defaults to the release the workspace already depends on.
            refreshDetectedVersions()
            detectedVersions.firstOrNull()?.let { detected -> setPinnedVersionText(detected.version) }
        }
    }

    /** The exact version typed or picked; detected entries and the `Select…` sentinel never leak their labels. */
    fun pinnedVersionText(): String =
        when (val item = pinnedVersion.editor.item) {
            is PinnedVersionItem -> item.version
            else -> item?.toString().orEmpty().trim()
        }

    private fun setPinnedVersionText(version: String) {
        val matching = detectedVersions.firstOrNull { it.version == version }
        pinnedVersion.selectedItem = matching?.let { PinnedVersionItem.Detected(it) } ?: version
        pinnedVersion.editor.item = version
    }

    private var detectedVersions: List<DetectedTsgoVersion> = emptyList()

    private fun refreshDetectedVersions() {
        detectedVersions = project.basePath?.takeIf(String::isNotBlank)
            ?.let { basePath -> runCatching { EffectWorkspaceTsgoDetector.detect(Path.of(basePath)) }.getOrDefault(emptyList()) }
            .orEmpty()
        val current = pinnedVersionText()
        val items = detectedVersions.map { detected -> PinnedVersionItem.Detected(detected) as PinnedVersionItem } + PinnedVersionItem.SELECT
        pinnedVersion.model = DefaultComboBoxModel(items.toTypedArray())
        pinnedVersion.editor.item = current
    }

    private fun choosePinnedVersionFromPackage() {
        val previous = pinnedVersionText()
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false).apply {
            title = "Select @effect/tsgo package"
            description = "Choose a node_modules/@effect/tsgo (or @effect/tsgo-<platform>) directory or its package.json to pin its version."
            withFileFilter { file -> file.isDirectory || file.name == "package.json" }
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null)
        val detected = chosen?.let { file -> EffectWorkspaceTsgoDetector.fromPackageDirectory(Path.of(file.path)) }
        when {
            chosen == null -> setPinnedVersionText(previous)
            detected == null -> {
                setPinnedVersionText(previous)
                validationLabel.text = "${chosen.presentableUrl} is not an @effect/tsgo package with an exact version."
            }
            else -> {
                if (detectedVersions.none { it.version == detected.version }) {
                    detectedVersions = detectedVersions + detected
                    val items = detectedVersions.map { PinnedVersionItem.Detected(it) as PinnedVersionItem } + PinnedVersionItem.SELECT
                    pinnedVersion.model = DefaultComboBoxModel(items.toTypedArray())
                }
                setPinnedVersionText(detected.version)
                clearProblems()
            }
        }
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

    private fun parseOptionalBoolean(value: Any?): Boolean? =
        when (value?.toString()?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun parseOptionalNonNegativeInt(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return trimmed.toIntOrNull() ?: -1
    }

    companion object {
        private const val SERVER_DEFAULT_OPTION = "Server default"
        private const val DEBUGGER_NOTICE =
            "Attach-time instrumentation is available for active debug sessions. Optional NODE_OPTIONS " +
                "injection applies to Node.js run/debug configurations; live Context, Span Stack, Fibers, " +
                "and Breakpoints snapshots remain best-effort while the JetBrains debugger bridge matures."
    }
}

/** Entries of the pinned-version picker: detected workspace releases plus the `Select…` chooser sentinel. */
internal sealed class PinnedVersionItem(val version: String, val label: String) {
    class Detected(val detected: DetectedTsgoVersion) : PinnedVersionItem(
        detected.version,
        "${detected.version}  —  ${detected.packageName} (${detected.source.parent})",
    )

    object SELECT : PinnedVersionItem("", "Select…")

    /** The editable combo box shows this for a selected item, so it must be the bare version. */
    override fun toString(): String = version
}
