package com.surrealdb.surql.run

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.surrealdb.surql.settings.SurQLSettings

private const val NOTIFICATION_GROUP = "SurrealQL"
private const val TOOL_WINDOW_ID = "SurrealQL Results"

/**
 * Renders a clickable "▶ Run" code vision lens above the first line of each SurrealQL statement
 * in `.surql` / `.surrealql` files.
 *
 * Clicking the lens executes the statement against the configured SurrealDB endpoint (via
 * [SurQLQueryRunner]) on a pooled background thread and displays the result in the
 * [SurQLResultsPanel] hosted by the "SurrealQL Results" tool window.
 */
class SurQLCodeVisionProvider : CodeVisionProvider<Boolean> {

    companion object {
        const val ID = "SurQLRunQuery"
    }

    override val id: String = ID
    override val name: String = "SurrealQL Run Query"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    /**
     * Runs on the EDT to determine whether the current editor contains a SurrealQL file.
     * The result is forwarded to [computeCodeVision] as [uiData].
     */
    override fun precomputeOnUiThread(editor: Editor): Boolean {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return file.name.endsWith(".surql") || file.name.endsWith(".surrealql")
    }

    /**
     * Runs on a background thread. Parses the document text and produces one "▶ Run" entry per
     * statement. Bails out immediately for non-SurrealQL files.
     */
    override fun computeCodeVision(editor: Editor, uiData: Boolean): CodeVisionState {
        if (!uiData) return CodeVisionState.READY_EMPTY
        val text = editor.document.text
        val statements = findStatements(text)
        if (statements.isEmpty()) return CodeVisionState.READY_EMPTY

        val entries: List<Pair<TextRange, CodeVisionEntry>> = statements.map { stmt ->
            val range = TextRange(stmt.startOffset, stmt.endOffset)
            val entry: CodeVisionEntry = ClickableTextCodeVisionEntry(
                text = "▶ Run",
                providerId = ID,
                onClick = { _, clickEditor ->
                    val project = clickEditor.project
                    if (project != null) {
                        // Extract USE NS / USE DB from the live document text so that
                        // individual statement runs honour file-level context declarations.
                        val (fileNs, fileDb) = extractUseDirectives(clickEditor.document.text)
                        handleRun(project, stmt.queryText, fileNs, fileDb)
                    }
                },
            )
            Pair(range, entry)
        }
        return CodeVisionState.Ready(entries)
    }

    private fun handleRun(
        project: Project,
        query: String,
        fileNamespace: String?,
        fileDatabase: String?,
    ) {
        val settings = SurQLSettings.getInstance()
        if (settings.surrealEndpoint.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                ?.createNotification(
                    "SurrealQL: No endpoint configured",
                    "Configure a SurrealDB endpoint under Settings → Tools → SurrealQL before running queries.",
                    NotificationType.WARNING,
                )
                ?.notify(project)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                SurQLQueryRunner.execute(query, fileNamespace, fileDatabase)
            } catch (e: Exception) {
                QueryResult(
                    query = query,
                    status = "ERR",
                    time = "",
                    rows = emptyList(),
                    error = e.message ?: "Unexpected error",
                    rawJson = "",
                )
            }
            ApplicationManager.getApplication().invokeLater {
                showResult(project, result)
            }
        }
    }

    private fun showResult(project: Project, result: QueryResult) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.getContent(0)?.component as? SurQLResultsPanel
            panel?.addResult(result)
        }
    }
}
