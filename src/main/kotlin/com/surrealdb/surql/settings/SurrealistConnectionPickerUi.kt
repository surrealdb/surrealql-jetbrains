package com.surrealdb.surql.settings

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.surrealdb.surql.connection.SANDBOX_CONNECTION_ID
import com.surrealdb.surql.connection.SurrealistConfigService
import com.surrealdb.surql.connection.SurrealistConnection
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList

/**
 * Shared dropdown model for choosing which Surrealist-sourced connection
 * (plus sandbox) the language server and "Open in Surrealist" use.
 *
 * Cloud-auth connections are omitted upstream in [SurrealistConfigService].
 */
internal object SurrealistConnectionPickerUi {

    sealed class Option {
        abstract val id: String
        abstract val label: String
        open val tooltip: String? = null

        data object Sandbox : Option() {
            override val id: String = SANDBOX_CONNECTION_ID
            override val label: String = "Sandbox (default \u2014 workspace-only inference)"
        }

        data class Surrealist(val connection: SurrealistConnection) : Option() {
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

        data class Missing(override val id: String) : Option() {
            override val label: String = "Unknown connection ($id) \u2014 falls back to Sandbox"
        }
    }

    fun createComboBox(): JComboBox<Option> =
        JComboBox<Option>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val component =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val option = value as? Option
                    text = option?.label ?: value?.toString().orEmpty()
                    toolTipText = option?.tooltip
                    return component
                }
            }
        }

    fun populate(combo: JComboBox<Option>, project: Project) {
        val service = SurrealistConfigService.getInstance()
        val savedId = SurQLProjectSettings.getInstance(project).selectedConnectionId
        val surrealistConnections = service.listConnections()

        val options = buildList {
            add(Option.Sandbox)
            surrealistConnections
                .filter { it.id != SANDBOX_CONNECTION_ID }
                .forEach { add(Option.Surrealist(it)) }

            if (savedId.isNotBlank() &&
                savedId != SANDBOX_CONNECTION_ID &&
                none { it.id == savedId }
            ) {
                add(Option.Missing(savedId))
            }
        }

        combo.model = DefaultComboBoxModel(options.toTypedArray())
        selectById(combo, savedId)
    }

    fun selectedConnectionId(combo: JComboBox<Option>?): String? =
        (combo?.selectedItem as? Option)?.id

    fun selectById(combo: JComboBox<Option>, savedId: String) {
        val model = combo.model
        val targetId = savedId.ifBlank { SANDBOX_CONNECTION_ID }
        val index = (0 until model.size).firstOrNull { model.getElementAt(it).id == targetId }
        combo.selectedIndex = index ?: 0
    }

    fun describeSource(): String {
        val service = SurrealistConfigService.getInstance()
        val path = service.configSource()
        return when {
            path != null -> "Loaded from: $path"
            service.isConfigMissing() ->
                "No Surrealist config found. Install Surrealist to populate this list."
            else -> ""
        }
    }

    fun createSourceLabel(): JBLabel =
        JBLabel(describeSource())
}
