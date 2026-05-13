package com.surrealdb.surql.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.surrealdb.surql.connection.CUSTOM_CONNECTION_ID
import com.surrealdb.surql.connection.SANDBOX_CONNECTION_ID
import com.surrealdb.surql.connection.SurrealistConfigService
import com.surrealdb.surql.connection.SurrealistConnection
import com.surrealdb.surql.lsp.SurQLLanguageServerFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList

/**
 * Per-project settings page nested under Settings → Tools → SurrealQL.
 *
 * The only thing it controls today is which connection — sandbox, a
 * Surrealist connection, or the application-level "Custom" override — is
 * used for the current project. Picking one writes to
 * [SurQLProjectSettings] and restarts the language server so the new
 * initialization options take effect immediately.
 */
class SurQLProjectSettingsConfigurable(private val project: Project) : Configurable {

    private var connectionCombo: JComboBox<ConnectionOption>? = null
    private var sourceLabel: JLabel? = null

    /** Single dropdown row: a sentinel (sandbox/custom) or a Surrealist connection. */
    private sealed class ConnectionOption {
        abstract val id: String
        abstract val label: String
        open val tooltip: String? = null

        data object Sandbox : ConnectionOption() {
            override val id: String = SANDBOX_CONNECTION_ID
            override val label: String = "Sandbox (default \u2014 workspace-only inference)"
        }

        data object Custom : ConnectionOption() {
            override val id: String = CUSTOM_CONNECTION_ID
            override val label: String = "Custom \u2014 use Settings \u2192 Tools \u2192 SurrealQL fields"
        }

        data class Surrealist(val connection: SurrealistConnection) : ConnectionOption() {
            override val id: String = connection.id
            override val label: String = buildLabel(connection)
            override val tooltip: String = "id: ${connection.id}"

            private companion object {
                fun buildLabel(con: SurrealistConnection): String {
                    val name = con.name.ifBlank { con.id }
                    val host = con.authentication.hostname.trim()
                    return if (host.isNotEmpty()) "$name \u2014 $host" else name
                }
            }
        }

        /**
         * Placeholder shown when the saved id no longer exists in
         * Surrealist's config (user deleted the connection there). Lets
         * the user see what's missing before we silently fall back to
         * sandbox at LSP-init time.
         */
        data class Missing(override val id: String) : ConnectionOption() {
            override val label: String = "Unknown connection ($id) \u2014 falls back to Sandbox"
        }
    }

    override fun getDisplayName(): String = "Project"

    override fun createComponent(): JComponent {
        val combo = JComboBox<ConnectionOption>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val option = value as? ConnectionOption
                    text = option?.label ?: value?.toString().orEmpty()
                    toolTipText = option?.tooltip
                    return component
                }
            }
        }
        connectionCombo = combo

        populate(combo)

        val refresh = JButton("Refresh").apply {
            addActionListener {
                SurrealistConfigService.getInstance().refresh()
                populate(combo)
            }
        }

        val source = JLabel(describeSource()).apply { putClientProperty("html.disable", true) }
        sourceLabel = source

        return panel {
            group("Active connection") {
                row("Connection:") {
                    cell(combo).align(AlignX.FILL)
                        .comment(
                            "Connections are imported from Surrealist's saved config. " +
                                "Pick <b>Sandbox</b> to run with workspace-only schema inference, or " +
                                "<b>Custom</b> to use the manual fields under " +
                                "<em>Settings &rarr; Tools &rarr; SurrealQL</em>."
                        )
                }
                row { cell(refresh) }
                row { cell(source) }
            }
        }
    }

    override fun isModified(): Boolean {
        val selectedId = (connectionCombo?.selectedItem as? ConnectionOption)?.id ?: return false
        return selectedId != SurQLProjectSettings.getInstance(project).selectedConnectionId
    }

    override fun apply() {
        val combo = connectionCombo ?: return
        val selectedId = (combo.selectedItem as? ConnectionOption)?.id ?: return
        SurQLProjectSettings.getInstance(project).selectedConnectionId = selectedId
        restartLanguageServer()
    }

    override fun reset() {
        val combo = connectionCombo ?: return
        selectInCombo(combo, SurQLProjectSettings.getInstance(project).selectedConnectionId)
    }

    override fun disposeUIResources() {
        connectionCombo = null
        sourceLabel = null
    }

    private fun populate(combo: JComboBox<ConnectionOption>) {
        val service = SurrealistConfigService.getInstance()
        val savedId = SurQLProjectSettings.getInstance(project).selectedConnectionId
        val surrealistConnections = service.listConnections()

        val options = buildList {
            add(ConnectionOption.Sandbox)
            surrealistConnections
                .filter { it.id != SANDBOX_CONNECTION_ID }
                .forEach { add(ConnectionOption.Surrealist(it)) }
            add(ConnectionOption.Custom)

            // Preserve the saved selection if it no longer matches anything
            // (e.g. user removed the connection from Surrealist) so the user
            // gets a visible "missing" entry instead of a silent reset.
            if (savedId.isNotBlank() &&
                savedId != SANDBOX_CONNECTION_ID &&
                savedId != CUSTOM_CONNECTION_ID &&
                none { it.id == savedId }
            ) {
                add(ConnectionOption.Missing(savedId))
            }
        }

        combo.model = DefaultComboBoxModel(options.toTypedArray())
        selectInCombo(combo, savedId)
        sourceLabel?.text = describeSource()
    }

    private fun selectInCombo(combo: JComboBox<ConnectionOption>, savedId: String) {
        val model = combo.model
        val targetId = savedId.ifBlank { SANDBOX_CONNECTION_ID }
        val index = (0 until model.size).firstOrNull { model.getElementAt(it).id == targetId }
        combo.selectedIndex = index ?: 0
    }

    private fun describeSource(): String {
        val service = SurrealistConfigService.getInstance()
        val path = service.configSource()
        return when {
            path != null -> "Loaded from: $path"
            service.isConfigMissing() ->
                "No Surrealist config found. Install Surrealist to populate this list."
            else -> ""
        }
    }

    private fun restartLanguageServer() {
        val manager = LanguageServerManager.getInstance(project)
        val settings = SurQLSettings.getInstance()
        // Capture the intended on/off state before `stop()` so we can restore
        // it: LanguageServerManager.stop() internally calls setEnabled(false),
        // which overwrites SurQLSettings.lspEnabled via our factory's
        // setEnabled override. Same workaround as the application configurable.
        val intendedLspEnabled = settings.lspEnabled
        try {
            manager.stop(SurQLLanguageServerFactory.SERVER_ID_VALUE)
            settings.lspEnabled = intendedLspEnabled
            manager.start(SurQLLanguageServerFactory.SERVER_ID_VALUE)
        } catch (_: Throwable) {
            // Server may not be running yet; LSP4IJ will start it lazily
            // when the user next opens a .surql file with the new options.
        }
    }
}
