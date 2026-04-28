package com.surrealdb.surql.settings

import com.intellij.util.messages.Topic

interface SurQLSettingsListener {
    fun settingsChanged()

    companion object {
        val TOPIC: Topic<SurQLSettingsListener> = Topic.create(
            "SurrealQL Settings Changed",
            SurQLSettingsListener::class.java,
        )
    }
}
