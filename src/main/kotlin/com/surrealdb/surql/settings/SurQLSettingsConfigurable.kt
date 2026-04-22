package com.surrealdb.surql.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.surrealdb.surql.SurQLGrammarService
import org.jetbrains.plugins.textmate.TextMateService
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Label shown as the first entry when the user wants automatic latest-version tracking. */
private const val LATEST_LABEL = "Latest (automatically updated)"

class SurQLSettingsConfigurable : Configurable {

    private var versionCombo: JComboBox<String>? = null

    override fun getDisplayName(): String = "SurrealQL"

    override fun createComponent(): JComponent {
        val combo = JComboBox(DefaultComboBoxModel(arrayOf(LATEST_LABEL)))
        versionCombo = combo

        // Populate the combo box from whatever the service has already fetched.
        // If the list is not ready yet we start a pooled thread that waits, then
        // populates on the EDT once the data arrives.
        populateCombo(combo)

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
        }

        return panel
    }

    override fun isModified(): Boolean {
        val settings = SurQLSettings.getInstance()
        val selected = selectedVersion()
        return selected != settings.selectedVersion
    }

    override fun apply() {
        val settings = SurQLSettings.getInstance()
        settings.selectedVersion = selectedVersion()
        // Trigger an immediate grammar reload without requiring an IDE restart.
        TextMateService.getInstance().reloadEnabledBundles()
    }

    override fun reset() {
        val settings = SurQLSettings.getInstance()
        val combo = versionCombo ?: return
        val saved = settings.selectedVersion
        if (saved.isBlank()) {
            combo.selectedIndex = 0
        } else {
            val idx = (0 until combo.model.size).firstOrNull { combo.model.getElementAt(it) == saved }
            if (idx != null) combo.selectedIndex = idx else combo.selectedIndex = 0
        }
    }

    override fun disposeUIResources() {
        versionCombo = null
    }

    // --- helpers ---

    /** Returns the raw stored value: empty string for "latest", tag for a pinned version. */
    private fun selectedVersion(): String {
        val item = versionCombo?.selectedItem as? String ?: return ""
        return if (item == LATEST_LABEL) "" else item
    }

    private fun populateCombo(combo: JComboBox<String>) {
        val service = SurQLGrammarService.getInstance()
        val versions = service.getAvailableVersions()

        if (versions.isNotEmpty()) {
            fillCombo(combo, versions, service.getLatestTag())
            return
        }

        // Versions not yet loaded — fetch asynchronously.
        ApplicationManager.getApplication().executeOnPooledThread {
            // Poll briefly; the service fetches on its own thread at startup.
            var waited = 0
            while (SurQLGrammarService.getInstance().getAvailableVersions().isEmpty() && waited < 5_000) {
                Thread.sleep(200)
                waited += 200
            }
            val loaded = SurQLGrammarService.getInstance().getAvailableVersions()
            SwingUtilities.invokeLater {
                if (combo.isDisplayable) {
                    fillCombo(combo, loaded, SurQLGrammarService.getInstance().getLatestTag())
                }
            }
        }
    }

    private fun fillCombo(combo: JComboBox<String>, versions: List<String>, latestTag: String?) {
        val latestLabel = if (latestTag != null) "Latest ($latestTag)" else LATEST_LABEL
        val model = DefaultComboBoxModel<String>()
        model.addElement(latestLabel)
        versions.forEach { model.addElement(it) }
        combo.model = model

        // Restore saved selection.
        val saved = SurQLSettings.getInstance().selectedVersion
        if (saved.isNotBlank()) {
            val idx = (0 until model.size).firstOrNull { model.getElementAt(it) == saved }
            if (idx != null) combo.selectedIndex = idx
        }
    }
}
