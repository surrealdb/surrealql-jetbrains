package com.surrealdb.surql

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.surrealdb.surql.settings.SurQLSettings
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val LOG = logger<SurQLGrammarService>()

private const val RELEASES_URL =
    "https://api.github.com/repos/surrealdb/surrealql-vsx/releases"
private const val RAW_BASE =
    "https://raw.githubusercontent.com/surrealdb/surrealql-vsx"
private const val NOTIFICATION_GROUP = "SurrealQL"

// Matches: "tag_name": "v0.3.0"
private val TAG_REGEX = Regex(""""tag_name"\s*:\s*"([^"]+)"""")

@Service(Service.Level.APP)
class SurQLGrammarService {

    // All available version tags fetched from GitHub, in descending order.
    @Volatile
    private var availableVersions: List<String> = emptyList()

    // The resolved "latest" tag (first item in the release list).
    @Volatile
    private var latestTag: String? = null

    // Paths for each downloaded version: tag → directory on the real filesystem.
    private val versionPaths = mutableMapOf<String, Path>()

    init {
        ApplicationManager.getApplication().executeOnPooledThread { fetchReleases() }
    }

    // --- Public API ---

    fun getAvailableVersions(): List<String> = availableVersions

    fun getLatestTag(): String? = latestTag

    /**
     * Returns the grammar directory for the currently selected version, downloading it
     * first if it has not been cached yet.  Returns null while the initial fetch is still
     * in progress (caller falls back to the built-in JAR grammar in that case).
     */
    fun getCurrentPath(): Path? {
        val settings = SurQLSettings.getInstance()
        val tag = settings.selectedVersion.ifBlank { latestTag } ?: return null
        return getOrDownload(tag)
    }

    // --- Internal ---

    private fun fetchReleases() {
        try {
            val body = httpGet(RELEASES_URL) ?: return
            val tags = TAG_REGEX.findAll(body).map { it.groupValues[1] }.toList()
            availableVersions = tags
            latestTag = tags.firstOrNull()
            // Pre-download the latest version so it is ready immediately.
            latestTag?.let { getOrDownload(it) }
        } catch (e: Exception) {
            LOG.info("Could not fetch SurrealQL grammar releases (offline?): ${e.message}")
        }
    }

    private fun getOrDownload(tag: String): Path? {
        versionPaths[tag]?.let { if (Files.isDirectory(it)) return it }
        return downloadVersion(tag)
    }

    private fun downloadVersion(tag: String): Path? {
        val cacheRoot = Path.of(FileUtil.getTempDirectory(), "surql-grammar-cache")
        val dir = cacheRoot.resolve(tag)

        return try {
            Files.createDirectories(dir)

            // Grammar file lives under syntaxes/ in the repo.
            downloadFileTo(
                "$RAW_BASE/$tag/syntaxes/surrealql.tmLanguage.json",
                dir.resolve("surrealql.tmLanguage.json"),
            )
            downloadFileTo(
                "$RAW_BASE/$tag/language-configuration.json",
                dir.resolve("language-configuration.json"),
            )
            // package.json: copy the JetBrains-specific wrapper from the plugin JAR so
            // IntelliJ sees the correct grammar path (./surrealql.tmLanguage.json) instead
            // of the VS Code path (./syntaxes/surrealql.tmLanguage.json).
            SurQLGrammarService::class.java.classLoader
                .getResourceAsStream("textmate/surql/package.json")
                ?.use { Files.copy(it, dir.resolve("package.json"), StandardCopyOption.REPLACE_EXISTING) }
                ?: error("package.json not found in plugin resources")

            versionPaths[tag] = dir
            LOG.info("SurrealQL grammar $tag cached at $dir")
            dir
        } catch (e: Exception) {
            LOG.warn("Failed to download SurrealQL grammar $tag: ${e.message}")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                ?.createNotification(
                    "SurrealQL grammar download failed",
                    "Could not download grammar version $tag. Using built-in grammar instead.",
                    NotificationType.WARNING,
                )
                ?.notify(null)
            null
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        return if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    }

    private fun downloadFileTo(url: String, dest: Path) {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
        conn.inputStream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        fun getInstance(): SurQLGrammarService =
            ApplicationManager.getApplication().getService(SurQLGrammarService::class.java)
    }
}
