package dev.effect.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.effect.intellij.core.EffectPluginConstants

@Service(Service.Level.APP)
@State(
    name = "dev.effect.intellij.settings.EffectApplicationStateService",
    storages = [Storage(value = EffectPluginConstants.APPLICATION_STORAGE_FILE, roamingType = RoamingType.DISABLED)],
)
class EffectApplicationStateService : PersistentStateComponent<EffectApplicationState> {
    private var state = EffectApplicationState()

    override fun getState(): EffectApplicationState = state

    override fun loadState(state: EffectApplicationState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun currentState(): EffectApplicationState = state.copy()

    companion object {
        fun getInstance(): EffectApplicationStateService = service()
    }
}
