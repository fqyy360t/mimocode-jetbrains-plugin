package ai.mimo.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "MiMoSettings", storages = [Storage("mimo.xml")])
class MiMoSettings : PersistentStateComponent<MiMoSettings.State> {

    data class State(
        var executablePath: String? = null,
    )

    private var state = State()

    val executablePath: String? get() = state.executablePath

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun getInstance(): MiMoSettings = service()
    }
}
