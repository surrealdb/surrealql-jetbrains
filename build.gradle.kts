import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.surrealdb"
version = "0.1.0"

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

            <p>
              <img src="https://raw.githubusercontent.com/surrealdb-dev/surql-jetbrains/main/src/main/resources/images/thumbnail.png"
                   alt="SurrealQL syntax highlighting in the JetBrains editor"
                   width="640"/>
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
        changeNotes = """
            <h3>0.1.0 &mdash; Initial release</h3>
            <ul>
              <li>Syntax highlighting for <code>.surql</code> and <code>.surrealql</code> files via the
                  TextMate grammar from <a href="https://github.com/surrealdb/surrealql-vsx">surrealdb/surrealql-vsx</a></li>
              <li>Custom SurrealQL file icon in the project view and editor tabs</li>
              <li>Grammar version picker under <em>Settings &rarr; Tools &rarr; SurrealQL</em>:
                  pick the latest release or pin to any published version</li>
              <li>Live grammar swap &mdash; no IDE restart required when switching versions</li>
              <li>Offline fallback grammar bundled inside the plugin JAR for first-launch and
                  no-network scenarios</li>
            </ul>
        """.trimIndent()
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
