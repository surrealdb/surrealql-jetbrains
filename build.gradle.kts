import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.date
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "com.surrealdb"
version = "0.2.1"

changelog {
    version.set(project.version.toString())
    path.set(file("CHANGELOG.md").canonicalPath)
    header.set(provider { "[${version.get()}] - ${date()}" })
    headerParserRegex.set("""(\d+\.\d+(?:\.\d+)?)""".toRegex())
    itemPrefix.set("-")
    keepUnreleasedSection.set(true)
    unreleasedTerm.set("[Unreleased]")
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
    repositoryUrl.set("https://github.com/surrealdb/surrealql-jetbrains")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("org.jetbrains.plugins.textmate")
        // LSP4IJ (Red Hat) — open-source LSP client that works in Community editions.
        plugin("com.redhat.devtools.lsp4ij", "0.19.2")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.surrealdb.surql-jetbrains"
        name = "SurrealQL"
        version = project.version.toString()
        description = """
            <p>
              <strong>SurrealQL</strong> language support for JetBrains IDEs &mdash; brings first-class
              editing of <code>.surql</code> and <code>.surrealql</code> files to IntelliJ IDEA,
              PyCharm, WebStorm, GoLand, RustRover, DataGrip, and any other IntelliJ-based IDE.
            </p>

            <h3>Features</h3>
            <ul>
              <li>Syntax highlighting via the official TextMate grammar from
                  <a href="https://github.com/surrealdb/surrealql-vsx">surrealdb/surrealql-vsx</a></li>
              <li>Custom file icon for <code>.surql</code> and <code>.surrealql</code> files in the project view and editor tabs</li>
              <li>Grammar version picker &mdash; always stay on the latest release or pin to any older version, switched live without an IDE restart</li>
              <li>Offline fallback: the latest grammar at build time is bundled inside the plugin so highlighting works even without a network connection</li>
              <li>Settings page under <em>Settings &rarr; Tools &rarr; SurrealQL</em></li>
            </ul>

            <h3>Compatibility</h3>
            <p>Works in any IntelliJ Platform IDE on build <code>243</code> (2024.3) or newer.</p>

            <h3>Links</h3>
            <ul>
              <li><a href="https://surrealdb.com">SurrealDB</a></li>
              <li><a href="https://surrealdb.com/docs/surrealdb/surrealql">SurrealQL documentation</a></li>
              <li><a href="https://github.com/surrealdb-dev/surql-jetbrains">GitHub repository</a></li>
              <li><a href="https://github.com/surrealdb-dev/surql-jetbrains/issues">Issue tracker</a></li>
            </ul>
        """.trimIndent()
        // Marketplace change-notes are sourced from CHANGELOG.md via the
        // org.jetbrains.changelog plugin. The current version's section is
        // rendered as HTML; older versions are reachable via the marketplace
        // history page.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        vendor {
            name = "SurrealDB"
            url = "https://surrealdb.com"
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Disable bundled GitLab to prevent classloader conflict with the sandbox vcs-gitlab plugin.
tasks.named<RunIdeTask>("runIde") {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Didea.disabled.plugins=org.jetbrains.plugins.gitlab")
    }
}

// Development utility: refresh the bundled fallback grammar from surrealdb/surrealql-vsx main.
// Run this before cutting a new plugin release so the JAR ships the latest grammar as a fallback.
// Usage: ./gradlew updateGrammar
tasks.register("updateGrammar") {
    group = "surrealql"
    description = "Refreshes the bundled fallback grammar from surrealdb/surrealql-vsx main branch"
    doLast {
        val base = "https://raw.githubusercontent.com/surrealdb/surrealql-vsx/main"
        val destDir = file("src/main/resources/textmate/surql")
        mapOf(
            "$base/syntaxes/surrealql.tmLanguage.json" to "surrealql.tmLanguage.json",
            "$base/language-configuration.json" to "language-configuration.json",
        ).forEach { (url, fileName) ->
            uri(url).toURL().openStream().use { input ->
                file("$destDir/$fileName").outputStream().use { output -> input.copyTo(output) }
            }
            println("Downloaded $fileName")
        }
        println("Done. Commit changes in src/main/resources/textmate/surql/ before releasing.")
    }
}

// Release utility: download the surrealql-language-server binaries for every
// supported platform and stage them under src/main/resources/lsp/<os-arch>/
// so the plugin JAR can extract them on first run instead of hitting GitHub.
//
// This trades plugin JAR size (≈3-15 MB per binary) for cold-start latency
// (the LSP becomes usable in <1 s on first .surql open with no network).
// Run this before publishing a release. The destination directory is
// gitignored — these are reproducible build artifacts, not source.
//
// Usage:
//   ./gradlew downloadLspBinaries                # latest GitHub release
//   ./gradlew downloadLspBinaries -Plsp.tag=v0.1.2
//   ./gradlew downloadLspBinaries -Plsp.platforms=macos-arm64,linux-amd64
tasks.register("downloadLspBinaries") {
    group = "surrealql"
    description = "Downloads surrealql-language-server release binaries into src/main/resources/lsp/."
    doLast {
        val tag: String = (project.findProperty("lsp.tag") as String?)?.takeIf { it.isNotBlank() }
            ?: resolveLatestLspTag()
            ?: error("Could not resolve latest LSP release tag (offline?). Pass -Plsp.tag=<vX.Y.Z>.")

        val requestedPlatforms = (project.findProperty("lsp.platforms") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()

        val allAssets = listOf(
            "macos-arm64" to "surrealql-language-server-macos-arm64",
            "linux-amd64" to "surrealql-language-server-linux-amd64",
            "linux-arm64" to "surrealql-language-server-linux-arm64",
            "windows-amd64" to "surrealql-language-server-windows-amd64.exe",
        )

        val assets = if (requestedPlatforms == null) allAssets
        else allAssets.filter { it.first in requestedPlatforms }

        if (assets.isEmpty()) {
            error("No matching platforms in -Plsp.platforms. Valid: ${allAssets.joinToString(",") { it.first }}")
        }

        val resourcesRoot = file("src/main/resources/lsp")
        resourcesRoot.mkdirs()

        val downloadBase = "https://github.com/surrealdb/surrealql-language-server/releases/download"
        assets.forEach { (subdir, fileName) ->
            val dir = file("${resourcesRoot.path}/$subdir")
            dir.mkdirs()
            val dest = file("${dir.path}/$fileName")
            println("Downloading $fileName ($tag) -> ${dest.relativeTo(projectDir)}")
            downloadFollowingRedirects(uri("$downloadBase/$tag/$fileName").toURL(), dest)
        }

        println("Done. Bundled ${assets.size} LSP binar${if (assets.size == 1) "y" else "ies"} for tag $tag.")
        println("Run ./gradlew clean buildPlugin to package them into the plugin JAR.")
    }
}

fun resolveLatestLspTag(): String? = try {
    val url = URI.create("https://api.github.com/repos/surrealdb/surrealql-language-server/releases/latest").toURL()
    val conn = url.openConnection() as HttpURLConnection
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.connectTimeout = 8_000
    conn.readTimeout = 8_000
    if (conn.responseCode == 200) {
        val body = conn.inputStream.bufferedReader().readText()
        Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    } else null
} catch (_: Exception) {
    null
}

fun downloadFollowingRedirects(url: URL, dest: File) {
    var current: URL = url
    var hops = 0
    while (true) {
        val conn = current.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        when (val code = conn.responseCode) {
            in 200..299 -> {
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                return
            }
            in 300..399 -> {
                val location = conn.getHeaderField("Location")
                    ?: error("Redirect without Location header for $current")
                if (++hops > 5) error("Too many redirects fetching $url")
                current = URI.create(location).toURL()
            }
            else -> error("HTTP $code for $current")
        }
    }
}
