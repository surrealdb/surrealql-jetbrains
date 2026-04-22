package com.surrealdb.surql.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "SurQLSettings",
    storages = [Storage("surql-jetbrains.xml")],
)
class SurQLSettings : PersistentStateComponent<SurQLSettings.State> {

    data class State(
        /** Empty string means "always use latest". */
        var selectedVersion: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Empty string means "always use latest". */
    var selectedVersion: String
        get() = state.selectedVersion
        set(value) { state.selectedVersion = value }

    companion object {
        fun getInstance(): SurQLSettings =
            ApplicationManager.getApplication().getService(SurQLSettings::class.java)
    }
}
