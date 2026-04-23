package com.surrealdb.surql.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.surrealdb.surql.SurQLGrammarService
import com.surrealdb.surql.lsp.SurQLLanguageServerFactory
import com.surrealdb.surql.lsp.SurQLLanguageServerService
import org.jetbrains.plugins.textmate.TextMateService
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Label shown as the first entry when the user wants automatic latest-version tracking. */
private const val LATEST_LABEL = "Latest (automatically updated)"

class SurQLSettingsConfigurable : Configurable {

    private var versionCombo: JComboBox<String>? = null

    // --- Language server controls ---
    private var lspEnabled: JBCheckBox? = null
    private var lspVersionCombo: JComboBox<String>? = null
    private var lspBinaryOverride: TextFieldWithBrowseButton? = null
    private var endpointField: JBTextField? = null
    private var namespaceField: JBTextField? = null
    private var databaseField: JBTextField? = null
    private var usernameField: JBTextField? = null
    private var passwordField: JBPasswordField? = null
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

        val endpoint = JBTextField(SurQLSettings.getInstance().surrealEndpoint)
        endpointField = endpoint
        val namespace = JBTextField(SurQLSettings.getInstance().surrealNamespace)
        namespaceField = namespace
        val database = JBTextField(SurQLSettings.getInstance().surrealDatabase)
        databaseField = database
        val username = JBTextField(SurQLSettings.getInstance().surrealUsername)
        usernameField = username
        val password = JBPasswordField().apply { text = SurQLSettings.getInstance().surrealPassword }
        passwordField = password
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

                group("SurrealDB connection (optional)") {
                    row("Endpoint:") {
                        cell(endpoint).align(AlignX.FILL)
                            .comment("e.g. <code>ws://127.0.0.1:8000/rpc</code>")
                    }
                    row("Namespace:") { cell(namespace).align(AlignX.FILL) }
                    row("Database:") { cell(database).align(AlignX.FILL) }
                    row("Username:") { cell(username).align(AlignX.FILL) }
                    row("Password:") { cell(password).align(AlignX.FILL) }
                    row("Active auth context:") { cell(authContext).align(AlignX.FILL) }
                }

                row { cell(restartButton) }
            }
        }

        return panel
    }

    override fun isModified(): Boolean {
        val settings = SurQLSettings.getInstance()
        return selectedVersion(versionCombo) != settings.selectedVersion ||
            (lspEnabled?.isSelected ?: settings.lspEnabled) != settings.lspEnabled ||
            selectedVersion(lspVersionCombo) != settings.lspSelectedVersion ||
            (lspBinaryOverride?.text ?: "") != settings.lspBinaryOverride ||
            (endpointField?.text ?: "") != settings.surrealEndpoint ||
            (namespaceField?.text ?: "") != settings.surrealNamespace ||
            (databaseField?.text ?: "") != settings.surrealDatabase ||
            (usernameField?.text ?: "") != settings.surrealUsername ||
            String(passwordField?.password ?: charArrayOf()) != settings.surrealPassword ||
            (authContextField?.text ?: "") != settings.activeAuthContext
    }

    override fun apply() {
        val settings = SurQLSettings.getInstance()

        val grammarChanged = settings.selectedVersion != selectedVersion(versionCombo)
        val lspBinaryChanged = settings.lspSelectedVersion != selectedVersion(lspVersionCombo) ||
            settings.lspBinaryOverride != (lspBinaryOverride?.text ?: "")
        val initOptionsChanged = settings.surrealEndpoint != (endpointField?.text ?: "") ||
            settings.surrealNamespace != (namespaceField?.text ?: "") ||
            settings.surrealDatabase != (databaseField?.text ?: "") ||
            settings.surrealUsername != (usernameField?.text ?: "") ||
            settings.surrealPassword != String(passwordField?.password ?: charArrayOf()) ||
            settings.activeAuthContext != (authContextField?.text ?: "")
        val enabledChanged = settings.lspEnabled != (lspEnabled?.isSelected ?: settings.lspEnabled)

        settings.selectedVersion = selectedVersion(versionCombo)
        settings.lspEnabled = lspEnabled?.isSelected ?: settings.lspEnabled
        settings.lspSelectedVersion = selectedVersion(lspVersionCombo)
        settings.lspBinaryOverride = lspBinaryOverride?.text ?: ""
        settings.surrealEndpoint = endpointField?.text ?: ""
        settings.surrealNamespace = namespaceField?.text ?: ""
        settings.surrealDatabase = databaseField?.text ?: ""
        settings.surrealUsername = usernameField?.text ?: ""
        settings.surrealPassword = String(passwordField?.password ?: charArrayOf())
        settings.activeAuthContext = authContextField?.text ?: ""

        if (grammarChanged) {
            TextMateService.getInstance().reloadEnabledBundles()
        }
        if (lspBinaryChanged || initOptionsChanged || enabledChanged) {
            // Bounce the LSP so the new binary path / init options take effect.
            restartLanguageServer()
        }
    }

    override fun reset() {
        val settings = SurQLSettings.getInstance()
        resetCombo(versionCombo, settings.selectedVersion)
        resetCombo(lspVersionCombo, settings.lspSelectedVersion)
        lspEnabled?.isSelected = settings.lspEnabled
        lspBinaryOverride?.text = settings.lspBinaryOverride
        endpointField?.text = settings.surrealEndpoint
        namespaceField?.text = settings.surrealNamespace
        databaseField?.text = settings.surrealDatabase
        usernameField?.text = settings.surrealUsername
        passwordField?.text = settings.surrealPassword
        authContextField?.text = settings.activeAuthContext
    }

    override fun disposeUIResources() {
        versionCombo = null
        lspEnabled = null
        lspVersionCombo = null
        lspBinaryOverride = null
        endpointField = null
        namespaceField = null
        databaseField = null
        usernameField = null
        passwordField = null
        authContextField = null
    }

    // --- helpers ---

    /** Returns the raw stored value: empty string for "latest", tag for a pinned version. */
    private fun selectedVersion(combo: JComboBox<String>?): String {
        val item = combo?.selectedItem as? String ?: return ""
        return if (item == LATEST_LABEL || item.startsWith("Latest (")) "" else item
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

    private fun restartLanguageServer() {
        val openProjects = ProjectManager.getInstance().openProjects
        for (project in openProjects) {
            val manager = LanguageServerManager.getInstance(project)
            try {
                manager.stop(SurQLLanguageServerFactory.SERVER_ID_VALUE)
                manager.start(SurQLLanguageServerFactory.SERVER_ID_VALUE)
            } catch (_: Throwable) {
                // The server may not be running yet; LSP4IJ will start it on next file open.
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

        // Versions not yet loaded — poll on a pooled thread, then update on the EDT.
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
