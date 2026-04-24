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
        /** TextMate grammar version. Empty string means "always use latest". */
        var selectedVersion: String = "",

        /** Whether the SurrealQL language server should be started for .surql files. */
        var lspEnabled: Boolean = true,

        /** Pinned language-server release tag. Empty string means "always use latest". */
        var lspSelectedVersion: String = "",

        /** Optional absolute path to a user-supplied language-server binary. Takes precedence over the auto-downloaded binary when non-blank. */
        var lspBinaryOverride: String = "",

        /** SurrealDB connection settings forwarded to the language server as `surrealql.connection.*`. */
        var surrealEndpoint: String = "",
        var surrealNamespace: String = "",
        var surrealDatabase: String = "",
        var surrealUsername: String = "",
        // TODO: move to PasswordSafe in a follow-up — currently lives in plain-text XML state.
        var surrealPassword: String = "",

        /** Name of the auth context the server should activate (matches `surrealql.activeAuthContext`). */
        var activeAuthContext: String = "viewer",

        /**
         * Controls which sources the language server uses for schema inference.
         * Forwarded as `surrealql.metadata.mode`.
         *   "both"      – local workspace files + remote SurrealDB (default)
         *   "workspace" – local workspace files only
         *   "db"        – remote SurrealDB only
         */
        var inferenceMode: String = "both",
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

    var lspEnabled: Boolean
        get() = state.lspEnabled
        set(value) { state.lspEnabled = value }

    var lspSelectedVersion: String
        get() = state.lspSelectedVersion
        set(value) { state.lspSelectedVersion = value }

    var lspBinaryOverride: String
        get() = state.lspBinaryOverride
        set(value) { state.lspBinaryOverride = value }

    var surrealEndpoint: String
        get() = state.surrealEndpoint
        set(value) { state.surrealEndpoint = value }

    var surrealNamespace: String
        get() = state.surrealNamespace
        set(value) { state.surrealNamespace = value }

    var surrealDatabase: String
        get() = state.surrealDatabase
        set(value) { state.surrealDatabase = value }

    var surrealUsername: String
        get() = state.surrealUsername
        set(value) { state.surrealUsername = value }

    var surrealPassword: String
        get() = state.surrealPassword
        set(value) { state.surrealPassword = value }

    var activeAuthContext: String
        get() = state.activeAuthContext
        set(value) { state.activeAuthContext = value }

    var inferenceMode: String
        get() = state.inferenceMode
        set(value) { state.inferenceMode = value }

    companion object {
        fun getInstance(): SurQLSettings =
            ApplicationManager.getApplication().getService(SurQLSettings::class.java)
    }
}
