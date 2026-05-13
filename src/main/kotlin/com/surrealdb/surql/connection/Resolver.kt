package com.surrealdb.surql.connection

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.surrealdb.surql.settings.SurQLProjectSettings
import com.surrealdb.surql.settings.SurQLSettings

private val LOG = logger<ResolvedConnection>()

/**
 * The connection currently in effect for a given project, after merging:
 *
 *  1. the project-level pick stored in [SurQLProjectSettings]
 *  2. the global "custom" override stored in [SurQLSettings]
 *  3. the catalogue of connections [SurrealistConfigService] reads from
 *     Surrealist's `config.json`
 *
 * Callers should treat instances as immutable snapshots: re-resolve when
 * settings change rather than mutating fields. The shape is deliberately
 * narrow — everything downstream (the LSP init options builder and the
 * "Open in Surrealist" deep-link builder) only needs these fields.
 */
sealed class ResolvedConnection {

    /** Stable id used by the dropdown / project state. */
    abstract val id: String

    /** Human-readable label suitable for the dropdown or a notification. */
    abstract val displayName: String

    abstract val namespace: String
    abstract val database: String
    abstract val username: String
    abstract val password: String
    abstract val token: String

    /**
     * Surrealist's `authentication.mode` (`"root"`, `"namespace"`,
     * `"database"`, `"token"`, `"access"`, `"none"`, `"cloud"`, ...) when
     * known, or `null` when irrelevant (e.g. sandbox). The plugin doesn't
     * forward this to the language server today but the deep-link path
     * may want to in a future iteration.
     */
    open val mode: String? = null

    /**
     * `true` when this resolves to the in-memory sandbox. Used by the LSP
     * wiring (skip the `connection` block) and the deep-link wiring
     * (send `endpoint=mem://`).
     */
    open val isSandbox: Boolean = false

    /**
     * Returns the URL to send to the language server as
     * `connection.endpoint`, or `null` when no live DB should be attached
     * (sandbox, missing custom endpoint, ...). Mirrors `connectionUri()`
     * in `surrealist/src/util/helpers.tsx`.
     */
    open fun endpointForLsp(): String? = null

    /**
     * Returns the URL to put in the `endpoint=` query string of a
     * `surrealist://` deep link. Surrealist matches this against its own
     * connections by `protocol+hostname` (plus `ns`/`db`/`user`), so the
     * value must reach a network endpoint Surrealist understands.
     *
     * Sandbox returns `"mem://"` so Surrealist's deep-link resolver routes
     * to the in-memory sandbox; non-sandbox returns the same URL as
     * [endpointForLsp].
     */
    open fun endpointForDeepLink(): String? = endpointForLsp()

    /**
     * Default: the connection couldn't be resolved (no selection, or the
     * picked Surrealist id no longer exists) and the LSP should run with
     * workspace-only inference. Deep links default to Surrealist's
     * sandbox.
     */
    data object Sandbox : ResolvedConnection() {
        override val id: String = SANDBOX_CONNECTION_ID
        override val displayName: String = "Sandbox"
        override val namespace: String = ""
        override val database: String = ""
        override val username: String = ""
        override val password: String = ""
        override val token: String = ""
        override val isSandbox: Boolean = true

        override fun endpointForLsp(): String? = null
        override fun endpointForDeepLink(): String = "mem://"
    }

    /**
     * The user opted into the manual fields on the application-level
     * settings page. We use those values verbatim — the endpoint is what
     * they typed (e.g. `ws://127.0.0.1:8000/rpc`).
     */
    data class Custom(
        override val namespace: String,
        override val database: String,
        override val username: String,
        override val password: String,
        val endpoint: String,
    ) : ResolvedConnection() {
        override val id: String = CUSTOM_CONNECTION_ID
        override val displayName: String = "Custom"
        override val token: String = ""

        override fun endpointForLsp(): String? = endpoint.takeIf { it.isNotBlank() }
    }

    /**
     * The user picked a connection that Surrealist persisted in its
     * `config.json`. We rebuild the endpoint URL from the connection's
     * stored `protocol`/`hostname` so it stays in sync with whatever the
     * user typed in Surrealist's connection details panel.
     */
    data class Surrealist(val connection: SurrealistConnection) : ResolvedConnection() {
        private val auth: SurrealistAuthentication = connection.authentication

        override val id: String = connection.id
        override val displayName: String = connection.name.ifBlank { connection.id }
        override val namespace: String = auth.namespace.ifBlank { connection.lastNamespace }
        override val database: String = auth.database.ifBlank { connection.lastDatabase }
        override val username: String = auth.username
        override val password: String = auth.password
        override val token: String = auth.token
        override val mode: String? = auth.mode

        override fun endpointForLsp(): String? = buildEndpoint(auth.protocol, auth.hostname)
    }
}

/**
 * Resolves the active SurrealQL connection for [project].
 *
 * `null` project (e.g. an application-level callsite that doesn't know
 * about the active project) falls back to the global sandbox default — we
 * never silently reach into "any project's" state because that would leak
 * one project's credentials into another.
 */
fun resolveActiveConnection(project: Project?): ResolvedConnection {
    if (project == null) return ResolvedConnection.Sandbox

    val selectedId = SurQLProjectSettings.getInstance(project).selectedConnectionId.trim()
    return when {
        selectedId.isEmpty() -> ResolvedConnection.Sandbox
        selectedId == SANDBOX_CONNECTION_ID -> ResolvedConnection.Sandbox
        selectedId == CUSTOM_CONNECTION_ID -> customConnection()
        else -> {
            val match = SurrealistConfigService.getInstance().findById(selectedId)
            if (match != null) {
                ResolvedConnection.Surrealist(match)
            } else {
                // The user removed the connection from Surrealist after picking it
                // here. Falling back to sandbox keeps the editor usable; a future
                // iteration could surface this as a notification.
                LOG.warn("Selected Surrealist connection '$selectedId' not found; using sandbox.")
                ResolvedConnection.Sandbox
            }
        }
    }
}

private fun customConnection(): ResolvedConnection {
    val s = SurQLSettings.getInstance()
    return ResolvedConnection.Custom(
        namespace = s.surrealNamespace,
        database = s.surrealDatabase,
        username = s.surrealUsername,
        password = s.surrealPassword,
        endpoint = s.surrealEndpoint,
    )
}

/**
 * Mirrors `connectionUri()` in `surrealist/src/util/helpers.tsx`. We don't
 * want the plugin to drift away from Surrealist's URL conventions, because
 * the same URL is used both to match an existing Surrealist connection
 * (deep link) and to connect from the language server (LSP).
 *
 * Returns `null` for endpoints that can't be reached over the network
 * (sandbox `mem://`, browser-only `indxdb://`, empty hostname). Surrealist
 * historically stored `protocol` as one of `http|https|ws|wss|mem|indxdb`.
 */
private fun buildEndpoint(protocol: String, hostname: String): String? {
    val p = protocol.trim().ifEmpty { "ws" }
    val h = hostname.trim()
    if (p.equals("mem", ignoreCase = true)) return null
    if (h.isEmpty()) return null
    if (p.equals("indxdb", ignoreCase = true)) return null

    // Most users type the host as `127.0.0.1:8000`; some paste a full URL
    // that already contains the scheme. Treat the latter as the source of
    // truth so we don't accidentally produce `ws://ws://...`.
    val withScheme = if (h.contains("://")) h else "$p://$h"
    return ensureRpcPath(withScheme)
}

/** Append `/rpc` when the URL doesn't already specify a non-root path. */
private fun ensureRpcPath(url: String): String {
    val schemeSep = url.indexOf("://")
    if (schemeSep < 0) return url

    val pathStart = url.indexOf('/', startIndex = schemeSep + 3)
    val authority = if (pathStart < 0) url else url.substring(0, pathStart)
    val rawPath = if (pathStart < 0) "" else url.substring(pathStart)

    val path = when {
        rawPath.isEmpty() || rawPath == "/" -> "/rpc"
        rawPath.endsWith("/") -> "${rawPath}rpc"
        rawPath.endsWith("/rpc", ignoreCase = true) -> rawPath
        else -> rawPath
    }
    return authority + path
}
