package com.surrealdb.surql.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.surrealdb.surql.SurQLGrammarService
import com.surrealdb.surql.connection.SurrealistConfigService
import com.surrealdb.surql.lsp.SurQLLanguageServerFactory
import com.surrealdb.surql.lsp.SurQLLanguageServerService
import org.jetbrains.plugins.textmate.TextMateService
import javax.swing.DefaultComboBoxModel
import com.intellij.ui.components.JBLabel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Label shown as the first entry when the user wants automatic latest-version tracking. */
private const val LATEST_LABEL = "Latest (automatically updated)"

/** Human-readable labels for the inference mode combo box. */
private val INFERENCE_OPTIONS = arrayOf(
    "Both (workspace + database)",
    "Workspace only",
    "Database only",
)

/** Corresponding `surrealql.metadata.mode` values sent to the language server. */
private val INFERENCE_VALUES = arrayOf("both", "workspace", "db")

class SurQLSettingsConfigurable(private val project: Project) : Configurable {

    private var versionCombo: JComboBox<String>? = null

    // --- Language server controls ---
    private var lspEnabled: JBCheckBox? = null
    private var lspVersionCombo: JComboBox<String>? = null
    private var lspBinaryOverride: TextFieldWithBrowseButton? = null
    private var inferenceModeCombo: JComboBox<String>? = null
    private var codeLensEnabled: JBCheckBox? = null
    private var connectionCombo: JComboBox<SurrealistConnectionPickerUi.Option>? = null
    private var connectionSourceLabel: JBLabel? = null
    private var authContextField: JBTextField? = null

    override fun getDisplayName(): String = "SurrealQL"

    override fun createComponent(): JComponent {
        val combo = JComboBox(DefaultComboBoxModel(arrayOf(LATEST_LABEL)))
        versionCombo = combo
        populateCombo(combo, SurQLGrammarService.getInstance()::getAvailableVersions, SurQLGrammarService.getInstance()::getLatestTag)

        val lspCombo = JComboBox(DefaultComboBoxModel(arrayOf(LATEST_LABEL)))
        lspVersionCombo = lspCombo
        populateCombo(
            lspCombo,
            SurQLLanguageServerService.getInstance()::getAvailableVersions,
            SurQLLanguageServerService.getInstance()::getLatestTag,
            SurQLSettings.getInstance().lspSelectedVersion,
        )

        val enabled = JBCheckBox("Enable SurrealQL language server", SurQLSettings.getInstance().lspEnabled)
        lspEnabled = enabled

        val override = TextFieldWithBrowseButton().apply {
            text = SurQLSettings.getInstance().lspBinaryOverride
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select surrealql-language-server Binary")
                .withDescription("Leave empty to use the auto-downloaded binary")
            addBrowseFolderListener(null, descriptor)
        }
        lspBinaryOverride = override

        val inferenceCombo = JComboBox(DefaultComboBoxModel(INFERENCE_OPTIONS)).apply {
            selectedIndex = INFERENCE_VALUES.indexOf(SurQLSettings.getInstance().inferenceMode).coerceAtLeast(0)
        }
        inferenceModeCombo = inferenceCombo

        val codeLens = JBCheckBox("Enable code lens", SurQLSettings.getInstance().codeLensEnabled)
        codeLensEnabled = codeLens

        val connCombo = SurrealistConnectionPickerUi.createComboBox()
        connectionCombo = connCombo
        SurrealistConnectionPickerUi.populate(connCombo, project)

        val connectionRefresh = JButton("Refresh connections").apply {
            addActionListener {
                SurrealistConfigService.getInstance().refresh()
                SurrealistConnectionPickerUi.populate(connCombo, project)
                connectionSourceLabel?.text = SurrealistConnectionPickerUi.describeSource()
            }
        }

        val sourceLabel = SurrealistConnectionPickerUi.createSourceLabel().apply {
            putClientProperty("html.disable", true)
        }
        connectionSourceLabel = sourceLabel

        val authContext = JBTextField(SurQLSettings.getInstance().activeAuthContext)
        authContextField = authContext

        val restartButton = JButton("Restart language server").apply {
            addActionListener { restartLanguageServer() }
        }

        val panel = panel {
            group("Grammar") {
                row("Version:") {
                    cell(combo).align(AlignX.FILL)
                        .comment(
                            "Grammar versions are published at " +
                                "<a href=\"https://github.com/surrealdb/surrealql-vsx/releases\">" +
                                "surrealdb/surrealql-vsx releases</a>. " +
                                "Select <b>Latest</b> to always use the newest version."
                        )
                }
            }

            group("Language server") {
                row { cell(enabled) }
                row("Version:") {
                    cell(lspCombo).align(AlignX.FILL)
                        .comment(
                            "Language-server releases are published at " +
                                "<a href=\"https://github.com/surrealdb/surrealql-language-server/releases\">" +
                                "surrealdb/surrealql-language-server releases</a>."
                        )
                }
                row("Binary override:") {
                    cell(override).align(AlignX.FILL)
                        .comment(
                            "Optional. If set, this binary is used instead of the auto-downloaded release."
                        )
                }
                row("Inference source:") {
                    cell(inferenceCombo).align(AlignX.FILL)
                        .comment(
                            "Controls which sources the language server uses to infer table and field names. " +
                                "<b>Workspace</b> scans local <code>.surql</code> files; " +
                                "<b>Database</b> queries a connected SurrealDB instance."
                        )
                }

                row { cell(codeLens) }

                group("Database connection") {
                    row("Connection:") {
                        cell(connCombo).align(AlignX.FILL)
                            .comment(
                                "Same list Surrealist persists to disk (non-cloud connections only). " +
                                    "The language server and <b>Open in Surrealist</b> use this choice for the " +
                                    "current project. Pick <b>Sandbox</b> for workspace-only schema inference."
                            )
                    }
                    row { cell(connectionRefresh) }
                    row { cell(sourceLabel) }
                }

                row("Active auth context:") {
                    cell(authContext).align(AlignX.FILL)
                        .comment(
                            "Forwarded as <code>surrealql.activeAuthContext</code> to the language server."
                        )
                }

                row { cell(restartButton) }
            }
        }

        return panel
    }

    override fun isModified(): Boolean {
        val settings = SurQLSettings.getInstance()
        val savedConn = SurQLProjectSettings.getInstance(project).selectedConnectionId
        val uiConn = SurrealistConnectionPickerUi.selectedConnectionId(connectionCombo) ?: savedConn
        return selectedVersion(versionCombo) != settings.selectedVersion ||
            (lspEnabled?.isSelected ?: settings.lspEnabled) != settings.lspEnabled ||
            selectedVersion(lspVersionCombo) != settings.lspSelectedVersion ||
            (lspBinaryOverride?.text ?: "") != settings.lspBinaryOverride ||
            selectedInferenceMode() != settings.inferenceMode ||
            (codeLensEnabled?.isSelected ?: settings.codeLensEnabled) != settings.codeLensEnabled ||
            uiConn != savedConn ||
            (authContextField?.text ?: "") != settings.activeAuthContext
    }

    override fun apply() {
        val settings = SurQLSettings.getInstance()

        val grammarChanged = settings.selectedVersion != selectedVersion(versionCombo)
        val lspBinaryChanged = settings.lspSelectedVersion != selectedVersion(lspVersionCombo) ||
            settings.lspBinaryOverride != (lspBinaryOverride?.text ?: "")
        val initOptionsChanged = settings.inferenceMode != selectedInferenceMode() ||
            settings.activeAuthContext != (authContextField?.text ?: "") ||
            surrealistConnectionChanged()
        val enabledChanged = settings.lspEnabled != (lspEnabled?.isSelected ?: settings.lspEnabled)

        settings.selectedVersion = selectedVersion(versionCombo)
        settings.lspEnabled = lspEnabled?.isSelected ?: settings.lspEnabled
        settings.lspSelectedVersion = selectedVersion(lspVersionCombo)
        settings.lspBinaryOverride = lspBinaryOverride?.text ?: ""
        settings.inferenceMode = selectedInferenceMode()
        settings.codeLensEnabled = codeLensEnabled?.isSelected ?: settings.codeLensEnabled
        settings.activeAuthContext = authContextField?.text ?: ""

        SurrealistConnectionPickerUi.selectedConnectionId(connectionCombo)?.let { newConnId ->
            SurQLProjectSettings.getInstance(project).selectedConnectionId = newConnId
        }

        if (grammarChanged) {
            TextMateService.getInstance().reloadEnabledBundles()
        }
        if (lspBinaryChanged || initOptionsChanged || enabledChanged) {
            val intendedLspEnabled = lspEnabled?.isSelected ?: settings.lspEnabled
            restartLanguageServer(intendedLspEnabled)
        }
    }

    private fun surrealistConnectionChanged(): Boolean {
        val newId = SurrealistConnectionPickerUi.selectedConnectionId(connectionCombo) ?: return false
        return newId != SurQLProjectSettings.getInstance(project).selectedConnectionId
    }

    override fun reset() {
        val settings = SurQLSettings.getInstance()
        resetCombo(versionCombo, settings.selectedVersion)
        resetCombo(lspVersionCombo, settings.lspSelectedVersion)
        lspEnabled?.isSelected = settings.lspEnabled
        lspBinaryOverride?.text = settings.lspBinaryOverride
        inferenceModeCombo?.selectedIndex = INFERENCE_VALUES.indexOf(settings.inferenceMode).coerceAtLeast(0)
        codeLensEnabled?.isSelected = settings.codeLensEnabled
        val combo = connectionCombo
        if (combo != null) {
            SurrealistConnectionPickerUi.populate(combo, project)
        }
        connectionSourceLabel?.text = SurrealistConnectionPickerUi.describeSource()
        authContextField?.text = settings.activeAuthContext
    }

    override fun disposeUIResources() {
        versionCombo = null
        lspEnabled = null
        lspVersionCombo = null
        lspBinaryOverride = null
        inferenceModeCombo = null
        codeLensEnabled = null
        connectionCombo = null
        connectionSourceLabel = null
        authContextField = null
    }

    // --- helpers ---

    /** Returns the raw stored value: empty string for "latest", tag for a pinned version. */
    private fun selectedVersion(combo: JComboBox<String>?): String {
        val item = combo?.selectedItem as? String ?: return ""
        return if (item == LATEST_LABEL || item.startsWith("Latest (")) "" else item
    }

    /** Returns the `metadata.mode` value corresponding to the currently selected inference option. */
    private fun selectedInferenceMode(): String {
        val idx = inferenceModeCombo?.selectedIndex ?: 0
        return INFERENCE_VALUES.getOrElse(idx) { "both" }
    }

    private fun resetCombo(combo: JComboBox<String>?, saved: String) {
        if (combo == null) return
        if (saved.isBlank()) {
            combo.selectedIndex = 0
            return
        }
        val idx = (0 until combo.model.size).firstOrNull { combo.model.getElementAt(it) == saved }
        combo.selectedIndex = idx ?: 0
    }

    private fun restartLanguageServer(intendedLspEnabled: Boolean = SurQLSettings.getInstance().lspEnabled) {
        val openProjects = ProjectManager.getInstance().openProjects
        for (p in openProjects) {
            val manager = LanguageServerManager.getInstance(p)
            try {
                manager.stop(SurQLLanguageServerFactory.SERVER_ID_VALUE)
                SurQLSettings.getInstance().lspEnabled = intendedLspEnabled
                manager.start(SurQLLanguageServerFactory.SERVER_ID_VALUE)
            } catch (_: Throwable) {
            }
        }
    }

    private fun populateCombo(
        combo: JComboBox<String>,
        getVersions: () -> List<String>,
        getLatestTag: () -> String?,
        savedSelection: String = SurQLSettings.getInstance().selectedVersion,
    ) {
        val versions = getVersions()
        if (versions.isNotEmpty()) {
            fillCombo(combo, versions, getLatestTag(), savedSelection)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            var waited = 0
            while (getVersions().isEmpty() && waited < 5_000) {
                Thread.sleep(200)
                waited += 200
            }
            val loaded = getVersions()
            val latest = getLatestTag()
            SwingUtilities.invokeLater {
                if (combo.isDisplayable) {
                    fillCombo(combo, loaded, latest, savedSelection)
                }
            }
        }
    }

    private fun fillCombo(
        combo: JComboBox<String>,
        versions: List<String>,
        latestTag: String?,
        savedSelection: String,
    ) {
        val latestLabel = if (latestTag != null) "Latest ($latestTag)" else LATEST_LABEL
        val model = DefaultComboBoxModel<String>()
        model.addElement(latestLabel)
        versions.forEach { model.addElement(it) }
        combo.model = model

        if (savedSelection.isNotBlank()) {
            val idx = (0 until model.size).firstOrNull { model.getElementAt(it) == savedSelection }
            if (idx != null) combo.selectedIndex = idx
        }
    }
}
