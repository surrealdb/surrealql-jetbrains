package com.surrealdb.surql.connection

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = logger<SurrealistConfigService>()

/**
 * Id reserved for the in-memory sandbox connection in Surrealist's own config.
 *
 * Mirrors `SANDBOX` in `surrealist/src/constants.tsx`. We keep the value
 * duplicated here (rather than depending on a shared module) so the plugin
 * stays self-contained and doesn't need to know which Surrealist build the
 * user has installed.
 */
const val SANDBOX_CONNECTION_ID: String = "sandbox"

/**
 * `authentication.mode` value Surrealist uses for connections it manages on
 * the user's behalf via the SurrealDB Cloud sign-in flow. Cloud connections
 * authenticate via a session token Surrealist refreshes itself and don't
 * map cleanly onto the LSP's `username`/`password`/`token` model — exposing
 * them here would only confuse users who picked one and then couldn't get
 * the language server to connect. Users who do want their cloud instance
 * available in the IDE can create a separate non-cloud connection in
 * Surrealist with the credentials they want to use.
 */
private const val CLOUD_AUTH_MODE: String = "cloud"

/**
 * Slice of Surrealist's persisted config that the plugin cares about.
 *
 * Only the fields needed to build an LSP connection block or a deep-link
 * query string are deserialised; everything else (`savedQueries`, view
 * state, diagram preferences, ...) is ignored so future schema changes in
 * Surrealist don't break the plugin's read path.
 */
@Serializable
data class SurrealistConfig(
    val connections: List<SurrealistConnection> = emptyList(),
    val sandbox: SurrealistConnection? = null,
)

@Serializable
data class SurrealistConnection(
    val id: String = "",
    val name: String = "",
    val lastNamespace: String = "",
    val lastDatabase: String = "",
    val authentication: SurrealistAuthentication = SurrealistAuthentication(),
)

@Serializable
data class SurrealistAuthentication(
    val mode: String = "none",
    val protocol: String = "ws",
    val hostname: String = "",
    val username: String = "",
    val password: String = "",
    val namespace: String = "",
    val database: String = "",
    val token: String = "",
    val access: String = "",
    @SerialName("cloudInstance")
    val cloudInstance: String? = null,
)

/**
 * Reads the connections persisted by Surrealist's desktop app from disk so
 * the JetBrains plugin can offer them in its per-project settings.
 *
 * Path resolution mirrors `dirs::config_dir()` + the path layout in
 * `surrealist/src-tauri/src/paths.rs`:
 *
 *   - macOS:   `$HOME/Library/Application Support/SurrealDB/Surrealist/config.json`
 *   - Linux:   `$XDG_CONFIG_HOME/SurrealDB/Surrealist/config.json`
 *              (fallback `$HOME/.config/SurrealDB/Surrealist/config.json`)
 *   - Windows: `%APPDATA%\SurrealDB\Surrealist\config.json`
 *
 * Both the release (`Surrealist`) and preview (`SurrealistPreview`) builds
 * are checked. The release build wins when both exist; the preview build is
 * used as a fallback so developers running only the preview channel still
 * see their connections.
 *
 * Reads are cached in memory until [refresh] is called. The plugin's
 * configurable invokes [refresh] from its "Refresh" button so users can
 * pull in connections they just added in Surrealist without restarting the
 * IDE.
 */
@Service(Service.Level.APP)
class SurrealistConfigService {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    private var cachedConfig: SurrealistConfig? = null

    @Volatile
    private var cachedSource: Path? = null

    /**
     * Returns every Surrealist connection the plugin can target, prepended
     * with the sandbox entry so the dropdown always offers a "no live DB"
     * choice even when the config is empty (or missing). Cloud connections
     * are filtered out — see [CLOUD_AUTH_MODE].
     *
     * The sandbox returned here is synthesised when Surrealist's config
     * doesn't contain one (e.g. fresh install): the LSP and deep-link
     * resolvers only depend on its `id` being `"sandbox"`, so a stub is
     * sufficient.
     */
    fun listConnections(): List<SurrealistConnection> {
        val cfg = readConfig()
        val sandbox = cfg?.sandbox ?: SurrealistConnection(
            id = SANDBOX_CONNECTION_ID,
            name = "Sandbox",
            lastNamespace = "sandbox",
            lastDatabase = "sandbox",
            authentication = SurrealistAuthentication(mode = "none", protocol = "mem"),
        )
        val user = cfg?.connections.orEmpty().filter(::isSupported)
        return buildList {
            add(sandbox)
            addAll(user)
        }
    }

    /**
     * Returns the connection that matches [id], or `null` if it isn't in
     * Surrealist's config (or it's a cloud connection — see [listConnections]).
     * The sandbox is resolved from the dedicated `sandbox` field rather than
     * the `connections` array, matching how Surrealist itself stores it
     * (see `useConfigStore.modifyConnection`).
     */
    fun findById(id: String): SurrealistConnection? {
        if (id.isBlank()) return null
        if (id == SANDBOX_CONNECTION_ID) {
            return listConnections().firstOrNull { it.id == SANDBOX_CONNECTION_ID }
        }
        return readConfig()?.connections
            ?.firstOrNull { it.id == id }
            ?.takeIf(::isSupported)
    }

    /**
     * Cloud connections are silently excluded everywhere the plugin
     * surfaces saved Surrealist connections. Centralising the check here
     * keeps [listConnections] and [findById] in lock-step so a stale id in
     * project state can't sneak past the dropdown filter into the LSP /
     * deep-link path.
     */
    private fun isSupported(connection: SurrealistConnection): Boolean {
        val mode = connection.authentication.mode.trim()
        return !mode.equals(CLOUD_AUTH_MODE, ignoreCase = true)
    }

    /** Path the connections were last loaded from, or `null` if no config was found. */
    fun configSource(): Path? {
        readConfig()
        return cachedSource
    }

    /** `true` when neither the release nor preview Surrealist config exists on disk. */
    fun isConfigMissing(): Boolean {
        readConfig()
        return cachedSource == null
    }

    /** Drops the in-memory cache so the next [listConnections] hits disk again. */
    fun refresh() {
        cachedConfig = null
        cachedSource = null
    }

    private fun readConfig(): SurrealistConfig? {
        cachedConfig?.let { return it }

        val path = locateConfigFile() ?: return null
        val parsed = parseConfig(path) ?: return null
        cachedConfig = parsed
        cachedSource = path
        return parsed
    }

    private fun parseConfig(path: Path): SurrealistConfig? = try {
        val text = Files.readString(path)
        json.decodeFromString<SurrealistConfig>(text)
    } catch (t: Throwable) {
        // Surrealist may upgrade its schema between releases; we don't want
        // an unparseable config to break .surql editing. Log once and fall
        // through to "no Surrealist connections available".
        LOG.warn("Failed to parse Surrealist config at $path: ${t.message}")
        null
    }

    private fun locateConfigFile(): Path? {
        val base = configBaseDir() ?: return null
        val release = base.resolve("SurrealDB").resolve("Surrealist").resolve("config.json")
        if (Files.isRegularFile(release)) return release
        val preview = base.resolve("SurrealDB").resolve("SurrealistPreview").resolve("config.json")
        if (Files.isRegularFile(preview)) return preview
        return null
    }

    /**
     * Best-effort mirror of Rust `dirs::config_dir()`. The Tauri sidecar
     * stores Surrealist's config under whichever directory this returns,
     * so the plugin has to reproduce the same OS conventions to find it.
     */
    private fun configBaseDir(): Path? {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let(Paths::get)
        return when {
            SystemInfo.isMac -> home?.resolve("Library/Application Support")
            SystemInfo.isWindows -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                appData?.let(Paths::get)
                    ?: home?.resolve("AppData/Roaming")
            }
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                xdg?.let(Paths::get) ?: home?.resolve(".config")
            }
        }
    }

    companion object {
        fun getInstance(): SurrealistConfigService =
            ApplicationManager.getApplication().getService(SurrealistConfigService::class.java)
    }
}
