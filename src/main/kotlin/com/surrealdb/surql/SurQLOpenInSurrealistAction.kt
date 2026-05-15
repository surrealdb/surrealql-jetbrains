package com.surrealdb.surql

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.surrealdb.surql.connection.ResolvedConnection
import com.surrealdb.surql.connection.resolveActiveConnection
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Main-toolbar action that opens the currently selected SurrealQL file in
 * Surrealist. Registered in the `MainToolbarRight` group via plugin.xml and
 * shown only when the focused/selected editor holds a .surql/.surrealql file.
 *
 * The launch hands the file off via a
 * `surrealist://open?file=<path>&endpoint=…&ns=…&db=…&user=…` deep link, which
 * Surrealist's `tauri_plugin_deep_link` registers system-wide. Using the URL
 * scheme is more reliable than `open -a Surrealist <path>` because it works
 * even when the OS file-association for .surql/.surrealql is missing (typical
 * for dev builds and unsigned installs).
 *
 * The file path is passed through the query string rather than the URL path
 * to avoid macOS's `NSDocumentController` auto-reopen flow, which crashes
 * Surrealist on cold start when the path component ends in `.surql` (see the
 * `openInSurrealist` comment for the full story).
 */
class SurQLOpenInSurrealistAction :
    AnAction("Open in Surrealist", "Open the current SurrealQL file in Surrealist", SurQLIcons.SURREALIST) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = currentFile(e)
        e.presentation.isEnabledAndVisible = file != null && isSurqlFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = currentFile(e) ?: return
        openInSurrealist(project, file)
    }

    private fun currentFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.project?.let { FileEditorManager.getInstance(it).selectedFiles.firstOrNull() }

    private fun isSurqlFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext == "surql" || ext == "surrealql"
    }

    private fun openInSurrealist(project: Project, file: VirtualFile) {
        FileDocumentManager.getInstance().saveAllDocuments()

        // Catch the "no Surrealist installed" case up front so the user gets a
        // clear, actionable notification (with a Download CTA) instead of the
        // generic `open`/`xdg-open` failure they'd otherwise see after the
        // deep link bounces off an unregistered URL scheme.
        if (!isSurrealistInstalled()) {
            notifySurrealistNotFound(project)
            return
        }

        // Surrealist registers the `surrealist://` deep-link scheme with the OS
        // via tauri_plugin_deep_link, so handing the file off as a URL is more
        // reliable than `open -a Surrealist <path>` — it works even when the OS
        // file-association for .surql/.surrealql isn't wired up (typical for
        // dev builds and codesigning-less installs).
        //
        // CRITICAL: the file path must live in the query string, not the URL
        // path component. macOS routes `surrealist:///abs/file.surql` URLs
        // through `NSDocumentController`'s auto-reopen flow on cold start
        // (because Surrealist registers itself as a `.surql` handler via
        // tauri's `fileAssociations`), which fires `application:openURLs:`
        // before tao's event loop is initialised and aborts the process at
        // the FFI boundary. Keeping the file path off the URL path keeps
        // macOS from recognising the open as a document open and routes us
        // through the regular Apple Event handler instead — which works on
        // cold *and* warm starts.
        val deepLink = "surrealist://open?" + buildOpenQuery(file, resolveActiveConnection(project))

        val command: Array<String> = when {
            SystemInfo.isMac -> arrayOf("open", deepLink)
            SystemInfo.isWindows -> arrayOf("cmd", "/c", "start", "", deepLink)
            else -> arrayOf("xdg-open", deepLink)
        }

        try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            Thread {
                runCatching {
                    val exit = process.waitFor()
                    if (exit != 0) notifyLaunchFailure(project, "Surrealist exited with code $exit")
                }.onFailure { notifyLaunchFailure(project, it.message ?: it.javaClass.simpleName) }
            }.apply { isDaemon = true; name = "surql-open-in-surrealist" }.start()
        } catch (e: IOException) {
            notifyLaunchFailure(project, e.message ?: "could not launch Surrealist")
        } catch (e: SecurityException) {
            notifyLaunchFailure(project, e.message ?: "blocked by SecurityManager")
        }
    }

    private fun notifyLaunchFailure(project: Project, detail: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SurrealQL")
            .createNotification(
                "Could not open in Surrealist",
                "$detail. Make sure Surrealist is installed and the surrealist:// URL scheme is registered.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring(DOWNLOAD_CTA_LABEL) {
                BrowserUtil.browse(DOWNLOAD_URL)
            })
            .notify(project)
    }

    private fun notifySurrealistNotFound(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SurrealQL")
            .createNotification(
                "Surrealist installation could not be found",
                "Install the Surrealist desktop app to open SurrealQL files from the IDE.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring(DOWNLOAD_CTA_LABEL) {
                BrowserUtil.browse(DOWNLOAD_URL)
            })
            .notify(project)
    }

    /**
     * Best-effort check for a Surrealist install in the locations the Tauri
     * installers actually drop the app into. We deliberately do *not* shell
     * out to `mdfind`/`which` here: the action runs on the EDT and the few
     * `Files.exists` probes below are cheap, while a subprocess would add
     * latency every time the toolbar button is clicked.
     *
     * False negatives (user installed Surrealist somewhere bespoke) are
     * acceptable — the Download CTA still drops them on the right page —
     * but false positives must be avoided because they regress to the
     * generic launch-failure path with no Download button.
     *
     * Both release (`Surrealist`) and preview (`SurrealistPreview`) builds
     * are recognised so contributors running only the preview channel
     * aren't nagged.
     */
    private fun isSurrealistInstalled(): Boolean = when {
        SystemInfo.isMac -> macInstallCandidates().any(Files::exists)
        SystemInfo.isWindows -> windowsInstallCandidates().any(Files::exists)
        SystemInfo.isLinux -> linuxInstallCandidates().any(Files::exists)
        else -> true
    }

    private fun macInstallCandidates(): List<Path> {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        return buildList {
            add(Paths.get("/Applications/Surrealist.app"))
            add(Paths.get("/Applications/SurrealistPreview.app"))
            if (home != null) {
                add(Paths.get(home, "Applications/Surrealist.app"))
                add(Paths.get(home, "Applications/SurrealistPreview.app"))
            }
        }
    }

    private fun windowsInstallCandidates(): List<Path> {
        // Tauri's NSIS installer defaults to a per-user install under
        // %LOCALAPPDATA%\<ProductName>, but users can pick the per-machine
        // option which lands the exe in %ProgramFiles%\<ProductName>.
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val programFiles = System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() }
        val programFilesX86 = System.getenv("ProgramFiles(x86)")?.takeIf { it.isNotBlank() }
        return buildList {
            if (localAppData != null) {
                add(Paths.get(localAppData, "Surrealist", "Surrealist.exe"))
                add(Paths.get(localAppData, "Programs", "Surrealist", "Surrealist.exe"))
                add(Paths.get(localAppData, "SurrealistPreview", "SurrealistPreview.exe"))
                add(Paths.get(localAppData, "Programs", "SurrealistPreview", "SurrealistPreview.exe"))
            }
            if (programFiles != null) {
                add(Paths.get(programFiles, "Surrealist", "Surrealist.exe"))
                add(Paths.get(programFiles, "SurrealistPreview", "SurrealistPreview.exe"))
            }
            if (programFilesX86 != null) {
                add(Paths.get(programFilesX86, "Surrealist", "Surrealist.exe"))
                add(Paths.get(programFilesX86, "SurrealistPreview", "SurrealistPreview.exe"))
            }
        }
    }

    private fun linuxInstallCandidates(): List<Path> = listOf(
        // `.deb` / `.rpm` drop the launcher here.
        Paths.get("/usr/bin/surrealist"),
        Paths.get("/usr/local/bin/surrealist"),
        // Tauri's bundler also stages the unpacked app under /opt for some
        // distros; covering it keeps us from nagging users who installed
        // via the linuxdeploy AppDir path.
        Paths.get("/opt/Surrealist/surrealist"),
        Paths.get("/opt/surrealist/surrealist"),
    )

    /**
     * Build the full `surrealist://open?…` query string for a file open,
     * combining the target `file=` path with the resolved connection's
     * identifying fields.
     *
     * `file` is always emitted first so a quick eyeball of the URL makes the
     * primary intent (open this file) obvious in logs and notification
     * surfaces.
     */
    private fun buildOpenQuery(file: VirtualFile, resolved: ResolvedConnection): String {
        val pairs = buildList {
            add("file" to file.path)
            addAll(connectionPairs(resolved))
        }
        return pairs.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /**
     * Serialise the resolved connection into key/value pairs for the
     * `surrealist://open?…` deep link.
     *
     * Surrealist's [`resolveDeepLinkConnectionId`](https://github.com/surrealdb/surrealist)
     * matches incoming deep links against existing connections by
     * `protocol+hostname+namespace+database+username`, so we only need to
     * pass those identifying fields — not the password — when the user has
     * picked a Surrealist connection. That keeps the URL credential-free in
     * the common case while still letting Surrealist land on the right
     * saved connection.
     *
     * Sandbox sends `endpoint=mem://`, which Surrealist routes to its
     * in-memory sandbox connection (see `normalizeEndpointForMatch` in
     * `surrealist/src/util/deep-link.tsx`).
     *
     * Matching Surrealist connections never need `pass` in the URL because
     * the Surrealist-side resolver looks up the saved credential by id.
     */
    private fun connectionPairs(resolved: ResolvedConnection): List<Pair<String, String>> {
        val endpoint = resolved.endpointForDeepLink()?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return buildList {
            add("endpoint" to endpoint)
            resolved.namespace.trim().takeIf { it.isNotEmpty() }?.let { add("ns" to it) }
            resolved.database.trim().takeIf { it.isNotEmpty() }?.let { add("db" to it) }
            resolved.username.trim().takeIf { it.isNotEmpty() }?.let { add("user" to it) }
        }
    }

    /**
     * URL-encode a query-string *value*. `URLEncoder` uses `+` for spaces
     * (HTML form encoding) but `surrealist://` is a generic URI, where spaces
     * should be `%20` and `+` should be preserved literally.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")

    companion object {
        private const val DOWNLOAD_URL: String = "https://surrealdb.com/surrealist?download"
        private const val DOWNLOAD_CTA_LABEL: String = "Download Surrealist"
    }
}
