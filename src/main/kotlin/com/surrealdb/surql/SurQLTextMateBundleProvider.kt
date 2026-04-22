package com.surrealdb.surql

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SurQLTextMateBundleProvider : TextMateBundleProvider {

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
        val path = SurQLGrammarService.getInstance().getCurrentPath()
            ?: extractBuiltInToTemp()
            ?: return emptyList()
        return listOf(TextMateBundleProvider.PluginBundle("SurrealQL", path))
    }

    companion object {
        private val BUNDLE_FILES = listOf(
            "package.json",
            "surrealql.tmLanguage.json",
            "language-configuration.json",
        )

        private var builtInTempDir: Path? = null

        fun extractBuiltInToTemp(): Path? {
            builtInTempDir?.let { if (Files.exists(it)) return it }
            return try {
                val dir = Files.createTempDirectory("surql-textmate-builtin-")
                for (file in BUNDLE_FILES) {
                    val stream = SurQLTextMateBundleProvider::class.java.classLoader
                        .getResourceAsStream("textmate/surql/$file") ?: return null
                    stream.use { Files.copy(it, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING) }
                }
                builtInTempDir = dir
                dir
            } catch (_: Exception) {
                null
            }
        }
    }
}
