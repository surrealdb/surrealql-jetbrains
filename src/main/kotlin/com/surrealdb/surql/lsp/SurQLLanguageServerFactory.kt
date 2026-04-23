package com.surrealdb.surql.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.surrealdb.surql.settings.SurQLSettings

private val LOG = logger<SurQLLanguageServerFactory>()

private const val SERVER_ID = "surrealqlLanguageServer"
private const val NOTIFICATION_GROUP = "SurrealQL"

/** How long [SurQLLanguageServer.start] is willing to wait for the binary download to finish. */
private const val BINARY_RESOLVE_TIMEOUT_MS = 60_000L

/** LSP4IJ entry point that wires the surrealql-language-server binary into the IDE. */
class SurQLLanguageServerFactory : LanguageServerFactory, LanguageServerEnablementSupport {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        SurQLLanguageServer()

    override fun isEnabled(project: Project): Boolean =
        SurQLSettings.getInstance().lspEnabled

    override fun setEnabled(enabled: Boolean, project: Project) {
        SurQLSettings.getInstance().lspEnabled = enabled
    }

    companion object {
        const val SERVER_ID_VALUE: String = SERVER_ID
    }
}

/**
 * Stream-connection provider that launches the platform-appropriate
 * surrealql-language-server binary over stdio and forwards the workspace
 * settings as LSP `initializationOptions`.
 *
 * Binary resolution intentionally happens in [start] (not the constructor):
 * LSP4IJ constructs the provider on the EDT-adjacent path and we want to
 * avoid blocking that thread on a 15 MB GitHub download. By the time
 * [start] runs the binary is usually already on disk thanks to the
 * [SurQLLspStartupActivity] pre-warm; if not, we await its
 * [java.util.concurrent.CompletableFuture] with a generous timeout.
 */
private class SurQLLanguageServer : ProcessStreamConnectionProvider() {

    override fun start() {
        val service = SurQLLanguageServerService.getInstance()
        val binary = service.awaitBinaryPath(BINARY_RESOLVE_TIMEOUT_MS)
        if (binary == null) {
            LOG.warn("SurrealQL language-server binary unavailable; server will not start.")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                ?.createNotification(
                    "SurrealQL language server not started",
                    "The language-server binary has not finished downloading or no build is " +
                        "available for this platform. Check Settings → Tools → SurrealQL.",
                    NotificationType.WARNING,
                )
                ?.notify(null)
            throw CannotStartProcessException(
                "SurrealQL language-server binary is not available on this platform.",
            )
        }
        super.setCommands(listOf(binary.toAbsolutePath().toString()))
        super.start()
    }

    override fun getInitializationOptions(rootUri: VirtualFile?): Any =
        buildInitializationOptions()
}

/**
 * Builds the JSON-serialisable initialization options sent on `initialize`.
 *
 * The shape matches the `surrealql` key consumed by
 * `surrealql-language-server::config::ServerSettings::from_sources` — see
 * `surrealql-language-server/src/config.rs`.
 */
internal fun buildInitializationOptions(): Map<String, Any?> {
    val settings = SurQLSettings.getInstance()

    val connection = linkedMapOf<String, Any?>().apply {
        if (settings.surrealEndpoint.isNotBlank()) put("endpoint", settings.surrealEndpoint)
        if (settings.surrealNamespace.isNotBlank()) put("namespace", settings.surrealNamespace)
        if (settings.surrealDatabase.isNotBlank()) put("database", settings.surrealDatabase)
        if (settings.surrealUsername.isNotBlank()) put("username", settings.surrealUsername)
        if (settings.surrealPassword.isNotBlank()) put("password", settings.surrealPassword)
    }

    val surrealql = linkedMapOf<String, Any?>()
    if (connection.isNotEmpty()) surrealql["connection"] = connection
    if (settings.activeAuthContext.isNotBlank()) {
        surrealql["activeAuthContext"] = settings.activeAuthContext
    }
    surrealql["metadata"] = mapOf("mode" to settings.inferenceMode)

    return mapOf("surrealql" to surrealql)
}
