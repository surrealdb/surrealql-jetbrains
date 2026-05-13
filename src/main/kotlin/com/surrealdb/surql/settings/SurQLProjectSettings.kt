package com.surrealdb.surql.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project SurrealQL plugin state.
 *
 * Today this only stores which Surrealist connection should be used for the
 * project — i.e. which connection's credentials are forwarded to the
 * language server and used by "Open in Surrealist". Persisted under
 * `.idea/surrealql.xml`, so the choice is per-developer rather than
 * version-controlled (per the design decision recorded in the plan).
 *
 * Resolution semantics live in [com.surrealdb.surql.connection.resolveActiveConnection]:
 *
 *   - empty string                → default → sandbox (no live DB on the LSP side)
 *   - `"sandbox"`                 → explicit sandbox
 *   - `"custom"`                  → use the manual fields from [SurQLSettings]
 *   - anything else               → Surrealist connection id (matched against
 *                                   the saved `connections` array; missing ids
 *                                   fall back to sandbox)
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SurQLProjectSettings",
    storages = [Storage("surrealql.xml")],
)
class SurQLProjectSettings : PersistentStateComponent<SurQLProjectSettings.State> {

    data class State(
        /**
         * Surrealist connection id to use for this project, or one of the
         * `*_CONNECTION_ID` sentinels. Empty string means "not chosen yet"
         * and behaves the same as [com.surrealdb.surql.connection.SANDBOX_CONNECTION_ID].
         */
        var selectedConnectionId: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var selectedConnectionId: String
        get() = state.selectedConnectionId
        set(value) {
            state.selectedConnectionId = value
        }

    companion object {
        fun getInstance(project: Project): SurQLProjectSettings =
            project.getService(SurQLProjectSettings::class.java)
    }
}
