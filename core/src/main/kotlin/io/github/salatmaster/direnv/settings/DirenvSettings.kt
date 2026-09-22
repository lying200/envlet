package io.github.salatmaster.direnv.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Per-project plugin settings.
 *
 * Deliberately holds no loaded environment. direnv output frequently contains secrets and must
 * never reach disk, so the environment cache lives in memory in DirenvService instead.
 */
@Service(Service.Level.PROJECT)
// Modified for Envlet: keep fork settings independent of the upstream plugin.
@State(name = "EnvletSettings", storages = [Storage("envlet.xml")])
class DirenvSettings : PersistentStateComponent<DirenvSettings.State> {

    class State {
        @JvmField
        var enabled: Boolean = true

        @JvmField
        var executablePath: String = "direnv"

        @JvmField
        var autoLoad: Boolean = true

        @JvmField
        var watchFiles: Boolean = true

        /** The owner's shell already has direnv hooked; avoid preloading DIRENV_DIFF. */
        @JvmField
        var terminalUsesShellHook: Boolean = true

        @JvmField
        var autoGoToolchain: Boolean = true

        @JvmField
        var autoRustToolchain: Boolean = true

        /** Generous by default: a first nix or devbox build routinely takes minutes. */
        @JvmField
        var timeoutSeconds: Int = 120

        /**
         * Extra variables passed to direnv itself, e.g. NIX_PATH.
         * User-authored configuration, not direnv output, so persisting it is safe.
         */
        @JvmField
        var extraEnv: MutableMap<String, String> = linkedMapOf()
    }

    private val internalState = State()

    override fun getState(): State = internalState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, internalState)
    }

    fun timeoutMs(): Int = internalState.timeoutSeconds * 1_000

    companion object {
        fun getInstance(project: Project): DirenvSettings = project.service()
    }
}
