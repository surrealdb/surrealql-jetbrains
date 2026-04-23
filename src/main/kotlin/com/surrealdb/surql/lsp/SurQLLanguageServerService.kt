package com.surrealdb.surql.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.surrealdb.surql.settings.SurQLSettings
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val LOG = logger<SurQLLanguageServerService>()

private const val RELEASES_URL =
    "https://api.github.com/repos/surrealdb/surrealql-language-server/releases"
private const val DOWNLOAD_BASE =
    "https://github.com/surrealdb/surrealql-language-server/releases/download"
private const val NOTIFICATION_GROUP = "SurrealQL"

private const val PROP_LATEST_TAG = "com.surrealdb.surql.lsp.latestTag"
private const val PROP_AVAILABLE_TAGS = "com.surrealdb.surql.lsp.availableTags"
private const val PROP_TAGS_FETCHED_AT = "com.surrealdb.surql.lsp.tagsFetchedAtMs"
private const val TAGS_TTL_MS = 24L * 60L * 60L * 1000L

private val TAG_REGEX = Regex(""""tag_name"\s*:\s*"([^"]+)"""")

/**
 * Manages the surrealql-language-server binary lifecycle:
 * fetches available release tags from GitHub, downloads the asset matching
 * the host OS/arch on demand, caches it under the IDE's persistent system
 * directory (so it survives macOS temp-dir cleanups across sessions), and
 * exposes the resolved binary path to the LSP4IJ connection provider both
 * synchronously and asynchronously.
 */
@Service(Service.Level.APP)
class SurQLLanguageServerService {

    @Volatile
    private var availableVersions: List<String> = emptyList()

    @Volatile
    private var latestTag: String? = null

    /** Per-tag in-flight downloads, so concurrent callers don't race the same file write. */
    private val downloadFutures = ConcurrentHashMap<String, CompletableFuture<Path?>>()

    /** Cached resolved paths after a successful download / discovery. */
    private val versionPaths = ConcurrentHashMap<String, Path>()

    /** Completes once `latestTag` has been populated (from cache or GitHub). */
    private val tagReady = CompletableFuture<Unit>()

    init {
        // Hydrate cached release info synchronously so getBinaryPath() can return
        // immediately on warm starts. The background fetchReleases() then refreshes
        // it if the TTL has expired (or refreshes silently in the background otherwise).
        loadCachedReleaseInfo()
        ApplicationManager.getApplication().executeOnPooledThread { ensureReleasesFresh() }
    }

    fun getAvailableVersions(): List<String> = availableVersions

    fun getLatestTag(): String? = latestTag

    /**
     * Returns the path to the language-server binary that should be launched.
     *
     * Resolution order:
     *  1. If [SurQLSettings.lspBinaryOverride] is set, use that path verbatim.
     *  2. If a host-platform binary is bundled inside the plugin JAR (and not
     *     yet extracted to the persistent cache), extract it and use it.
     *  3. Otherwise download (or reuse the cached copy of) the configured release.
     *  4. Returns null while the initial release fetch is still in-flight or if
     *     the platform is unsupported (no asset published for it).
     *
     * This call may perform synchronous I/O. Prefer [awaitBinaryPath] from
     * latency-sensitive call sites.
     */
    fun getBinaryPath(): Path? {
        val settings = SurQLSettings.getInstance()

        val override = settings.lspBinaryOverride.trim()
        if (override.isNotEmpty()) {
            val path = Path.of(override)
            return if (Files.isRegularFile(path)) path else {
                LOG.warn("SurrealQL LSP override path does not exist: $override")
                null
            }
        }

        bundledBinary()?.let { return it }

        val tag = settings.lspSelectedVersion.ifBlank { latestTag } ?: return null
        return getOrDownload(tag)
    }

    /**
     * Async equivalent of [getBinaryPath]. Returns a future that completes once
     * the binary is available on disk (or `null` if the platform is unsupported
     * or the download has failed). Multiple concurrent callers share the same
     * underlying download future, so this is cheap to call repeatedly.
     */
    fun getBinaryPathAsync(): CompletableFuture<Path?> {
        val settings = SurQLSettings.getInstance()

        val override = settings.lspBinaryOverride.trim()
        if (override.isNotEmpty()) {
            val path = Path.of(override)
            return CompletableFuture.completedFuture(if (Files.isRegularFile(path)) path else null)
        }

        bundledBinary()?.let { return CompletableFuture.completedFuture(it) }

        val pinned = settings.lspSelectedVersion.ifBlank { null }
        if (pinned != null) {
            return getOrDownloadAsync(pinned)
        }

        // No pinned version yet — wait until the GitHub release-list fetch
        // completes (or the cached copy is hydrated), then trigger the download.
        return tagReady.thenCompose {
            val tag = latestTag
            if (tag == null) CompletableFuture.completedFuture(null)
            else getOrDownloadAsync(tag)
        }
    }

    /**
     * Blocks the calling thread until the LSP binary is available on disk, up
     * to [timeoutMillis]. Returns null on timeout / unsupported platform /
     * download failure. Designed to be called from
     * [SurQLLanguageServer.start] so LSP4IJ can launch the process as soon as
     * the binary is ready.
     */
    fun awaitBinaryPath(timeoutMillis: Long): Path? = try {
        getBinaryPathAsync().get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        LOG.info("Timed out / failed waiting for SurrealQL LSP binary: ${e.message}")
        null
    }

    /** Trigger a fresh release-list fetch (used by the settings UI's "Refresh" action). */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread { fetchReleases() }
    }

    /** Called from [SurQLLspStartupActivity] so the GitHub fetch + download warms at IDE boot. */
    internal fun prewarm() {
        getBinaryPathAsync()
    }

    // --- Internal ---

    private fun loadCachedReleaseInfo() {
        val props = PropertiesComponent.getInstance()
        val cachedTags = props.getValue(PROP_AVAILABLE_TAGS).orEmpty()
        if (cachedTags.isNotBlank()) {
            availableVersions = cachedTags.split('\n').filter { it.isNotBlank() }
        }
        val cachedLatest = props.getValue(PROP_LATEST_TAG).orEmpty()
        if (cachedLatest.isNotBlank()) {
            latestTag = cachedLatest
            tagReady.complete(Unit)
        }
    }

    private fun ensureReleasesFresh() {
        val fetchedAt = PropertiesComponent.getInstance()
            .getValue(PROP_TAGS_FETCHED_AT)?.toLongOrNull() ?: 0L
        val ageMs = System.currentTimeMillis() - fetchedAt
        if (ageMs < TAGS_TTL_MS && latestTag != null) {
            return
        }
        fetchReleases()
    }

    private fun fetchReleases() {
        try {
            val body = httpGet(RELEASES_URL) ?: return
            val tags = TAG_REGEX.findAll(body).map { it.groupValues[1] }.toList()
            if (tags.isEmpty()) return
            availableVersions = tags
            latestTag = tags.first()
            val props = PropertiesComponent.getInstance()
            props.setValue(PROP_AVAILABLE_TAGS, tags.joinToString("\n"))
            props.setValue(PROP_LATEST_TAG, tags.first())
            props.setValue(PROP_TAGS_FETCHED_AT, System.currentTimeMillis().toString())
            tagReady.complete(Unit)
            // Pre-warm the cache for the latest release so the first .surql open is fast.
            getOrDownloadAsync(tags.first())
        } catch (e: Exception) {
            LOG.info("Could not fetch SurrealQL language-server releases (offline?): ${e.message}")
            // Don't leave async waiters hanging forever — let them resolve to
            // null so getBinaryPathAsync() returns rather than blocking.
            tagReady.complete(Unit)
        }
    }

    private fun getOrDownload(tag: String): Path? {
        versionPaths[tag]?.let { if (Files.isRegularFile(it)) return it }
        // If a download is already in flight, wait for it instead of starting a new one.
        downloadFutures[tag]?.let {
            return runCatching { it.get(60, TimeUnit.SECONDS) }.getOrNull()
        }
        return downloadVersion(tag)
    }

    private fun getOrDownloadAsync(tag: String): CompletableFuture<Path?> {
        versionPaths[tag]?.let {
            if (Files.isRegularFile(it)) return CompletableFuture.completedFuture(it)
        }
        return downloadFutures.computeIfAbsent(tag) { tagToDownload ->
            CompletableFuture.supplyAsync(
                { downloadVersion(tagToDownload) },
                { task -> ApplicationManager.getApplication().executeOnPooledThread(task) },
            )
        }
    }

    private fun downloadVersion(tag: String): Path? {
        val asset = resolvePlatformAsset() ?: run {
            notifyUnsupportedPlatform()
            return null
        }
        val dir = lspCacheRoot().resolve(tag)

        return try {
            Files.createDirectories(dir)
            val dest = dir.resolve(asset.fileName)
            if (!Files.isRegularFile(dest)) {
                downloadFileTo("$DOWNLOAD_BASE/$tag/${asset.fileName}", dest)
                makeExecutable(dest)
            }
            if (asset.macX64Fallback) {
                notifyMacIntelFallback(tag)
            }
            versionPaths[tag] = dest
            LOG.info("SurrealQL language-server $tag cached at $dest")
            dest
        } catch (e: Exception) {
            LOG.warn("Failed to download SurrealQL language-server $tag: ${e.message}")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                ?.createNotification(
                    "SurrealQL language server download failed",
                    "Could not download language-server release $tag: ${e.message}. " +
                        "LSP features will be unavailable.",
                    NotificationType.WARNING,
                )
                ?.notify(null)
            null
        } finally {
            downloadFutures.remove(tag)
        }
    }

    /**
     * Returns the path to a host-platform binary bundled inside the plugin JAR
     * after extracting it to the persistent cache, or null if no such resource
     * is bundled. The `:bundled` cache key prevents collisions with
     * release-tagged downloads.
     */
    private fun bundledBinary(): Path? {
        val asset = resolvePlatformAsset() ?: return null
        val resourcePath = "/lsp/${asset.resourceDir}/${asset.fileName}"
        val resource = SurQLLanguageServerService::class.java.getResource(resourcePath) ?: return null

        val cachedKey = "bundled-${asset.resourceDir}"
        versionPaths[cachedKey]?.let { if (Files.isRegularFile(it)) return it }

        val dir = lspCacheRoot().resolve("bundled").resolve(asset.resourceDir)
        return try {
            Files.createDirectories(dir)
            val dest = dir.resolve(asset.fileName)
            if (!Files.isRegularFile(dest)) {
                resource.openStream().use { input ->
                    Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
                }
                makeExecutable(dest)
            }
            versionPaths[cachedKey] = dest
            dest
        } catch (e: Exception) {
            LOG.warn("Failed to extract bundled SurrealQL LSP binary: ${e.message}")
            null
        }
    }

    private fun resolvePlatformAsset(): PlatformAsset? {
        val arch = CpuArch.CURRENT
        val isArm64 = arch == CpuArch.ARM64
        val is64 = arch == CpuArch.X86_64 || isArm64

        return when {
            SystemInfo.isWindows && arch == CpuArch.X86_64 ->
                PlatformAsset(
                    fileName = "surrealql-language-server-windows-amd64.exe",
                    resourceDir = "windows-amd64",
                )
            SystemInfo.isMac && isArm64 ->
                PlatformAsset(
                    fileName = "surrealql-language-server-macos-arm64",
                    resourceDir = "macos-arm64",
                )
            SystemInfo.isMac && is64 ->
                // No x86_64 macOS asset in the LS CI matrix; fall back to the arm64
                // build via Rosetta and warn the user once per session.
                PlatformAsset(
                    fileName = "surrealql-language-server-macos-arm64",
                    resourceDir = "macos-arm64",
                    macX64Fallback = true,
                )
            SystemInfo.isLinux && isArm64 ->
                PlatformAsset(
                    fileName = "surrealql-language-server-linux-arm64",
                    resourceDir = "linux-arm64",
                )
            SystemInfo.isLinux && arch == CpuArch.X86_64 ->
                PlatformAsset(
                    fileName = "surrealql-language-server-linux-amd64",
                    resourceDir = "linux-amd64",
                )
            else -> null
        }
    }

    private fun makeExecutable(path: Path) {
        if (SystemInfo.isWindows) return
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, perms)
        } catch (e: Exception) {
            LOG.warn("Could not set executable bit on $path: ${e.message}")
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
        // GitHub release downloads redirect to a signed S3 URL; HttpURLConnection follows
        // 3xx redirects within the same protocol by default but will refuse https↔http
        // hops, so we do the redirect dance manually for robustness.
        var current = url
        var hops = 0
        while (true) {
            val conn = URI.create(current).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8_000
            conn.readTimeout = 30_000
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    conn.inputStream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
                    return
                }
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                        ?: error("Redirect without Location header for $current")
                    if (++hops > 5) error("Too many redirects fetching $url")
                    current = location
                }
                else -> error("HTTP $code for $current")
            }
        }
    }

    private fun notifyUnsupportedPlatform() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            ?.createNotification(
                "SurrealQL language server unsupported on this platform",
                "No prebuilt language-server binary is published for ${SystemInfo.OS_NAME} " +
                    "${System.getProperty("os.arch")}. LSP features will be disabled. " +
                    "Set a custom binary path in Settings → Tools → SurrealQL.",
                NotificationType.WARNING,
            )
            ?.notify(null)
    }

    @Volatile
    private var notifiedMacIntelFallback = false

    private fun notifyMacIntelFallback(tag: String) {
        if (notifiedMacIntelFallback) return
        notifiedMacIntelFallback = true
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            ?.createNotification(
                "SurrealQL language server: using arm64 build via Rosetta",
                "No native x86_64 macOS build is published for $tag — running the arm64 " +
                    "binary through Rosetta instead. Install Rosetta 2 if you have not already.",
                NotificationType.INFORMATION,
            )
            ?.notify(null)
    }

    /**
     * Persistent cache root for downloaded LSP binaries.
     *
     * Sits under [PathManager.getSystemPath] (`~/Library/Caches/JetBrains/IntelliJIdea<ver>`
     * on macOS, `~/.cache/JetBrains/IntelliJIdea<ver>` on Linux,
     * `%LOCALAPPDATA%/JetBrains/IntelliJIdea<ver>` on Windows). This avoids the
     * macOS temp-dir cleaner deleting the binary every few days, which used to
     * trigger a 15 MB GitHub re-download on every cold open.
     */
    private fun lspCacheRoot(): Path =
        Path.of(PathManager.getSystemPath()).resolve("surql-lsp")

    private data class PlatformAsset(
        val fileName: String,
        val resourceDir: String,
        val macX64Fallback: Boolean = false,
    )

    companion object {
        fun getInstance(): SurQLLanguageServerService =
            ApplicationManager.getApplication().getService(SurQLLanguageServerService::class.java)
    }
}
