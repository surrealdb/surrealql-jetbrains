package com.surrealdb.surql

import com.intellij.spellchecker.BundledDictionaryProvider

/**
 * Registers the SurrealQL dictionary so the IDE's spell checker doesn't flag
 * SurrealQL keywords (e.g. `SCHEMAFULL`, `SCHEMALESS`) as typos.
 */
class SurQLBundledDictionaryProvider : BundledDictionaryProvider {
    override fun getBundledDictionaries(): Array<String> = arrayOf("/META-INF/surql.dic")
}
