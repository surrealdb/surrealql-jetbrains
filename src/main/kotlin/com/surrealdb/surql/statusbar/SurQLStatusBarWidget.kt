package com.surrealdb.surql.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.surrealdb.surql.settings.SurQLSettings
import com.surrealdb.surql.settings.SurQLSettingsListener
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Status bar widget that displays the currently configured SurrealDB connection.
 *
 * Text format:
 *   - `SurrealQL: ws://localhost:8000 | mynamespace/mydb` when an endpoint is configured.
 *   - `SurrealQL: not configured` otherwise.
 *
 * The widget updates without polling by subscribing to [SurQLSettingsListener.TOPIC] on the
 * application message bus, which is published by [com.surrealdb.surql.settings.SurQLSettingsConfigurable]
 * whenever settings are saved.
 *
 * Clicking the widget opens Settings → Tools → SurrealQL.
 */
class SurQLStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val WIDGET_ID = "SurQLStatusBar"
    }

    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Subscribe on the application bus (settings are application-scoped).
        // Passing `this` as the Disposable parent ensures the connection is cleaned up
        // automatically when the widget is disposed via Disposer.
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(SurQLSettingsListener.TOPIC, object : SurQLSettingsListener {
                override fun settingsChanged() {
                    statusBar.updateWidget(ID())
                }
            })
    }

    override fun dispose() {
        statusBar = null
    }

    // --- TextPresentation ---

    override fun getText(): String {
        val s = SurQLSettings.getInstance()
        if (s.surrealEndpoint.isBlank()) return "SurrealQL: not configured"
        return buildString {
            append("SurrealQL: ")
            append(s.surrealEndpoint)
            if (s.surrealNamespace.isNotBlank() && s.surrealDatabase.isNotBlank()) {
                append(" | ${s.surrealNamespace}/${s.surrealDatabase}")
            }
        }
    }

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getTooltipText(): String {
        val s = SurQLSettings.getInstance()
        if (s.surrealEndpoint.isBlank()) {
            return "SurrealQL: no connection configured — click to open settings"
        }
        return buildString {
            append("SurrealQL connection\n")
            append("Endpoint: ${s.surrealEndpoint}")
            if (s.surrealNamespace.isNotBlank()) append("\nNamespace: ${s.surrealNamespace}")
            if (s.surrealDatabase.isNotBlank()) append("\nDatabase: ${s.surrealDatabase}")
            if (s.activeAuthContext.isNotBlank()) append("\nAuth context: ${s.activeAuthContext}")
            append("\n\nClick to open settings")
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "com.surrealdb.surql.settings")
    }
}
