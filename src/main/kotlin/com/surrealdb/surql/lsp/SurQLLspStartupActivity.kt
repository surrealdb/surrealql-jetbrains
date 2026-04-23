package com.surrealdb.surql.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Pre-warms the SurrealQL language-server binary at IDE / project startup.
 *
 * Without this, the GitHub release-list fetch and 15 MB binary download only
 * begin when the user opens their first .surql file — and because LSP4IJ
 * constructs the connection provider on the LSP-launch path, that download
 * directly delays "server starting" by up to ~30 s on a cold machine.
 *
 * Running it from a [ProjectActivity] means the work happens in the
 * background while the project is still loading, so by the time the user
 * touches a SurrealQL file the binary is almost always already on disk.
 */
internal class SurQLLspStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            SurQLLanguageServerService.getInstance().prewarm()
        }
    }
}
