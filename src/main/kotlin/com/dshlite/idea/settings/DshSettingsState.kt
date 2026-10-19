package com.dshlite.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "DshLiteSettings", storages = [Storage("dsh-lite.xml")])
@Service(Service.Level.APP)
class DshSettingsState : PersistentStateComponent<DshSettingsState.Data> {

    class Data {
        /** auto | wsl | windows */
        var runtime: String = "auto"
        var command: String = "npx -y @deepseek-ai/dsh"
        var profile: String = "acp-vscode"
        var wslDistro: String = ""
        /** Extra environment variables, one `KEY=VALUE` per line. */
        var env: String = ""
        /** One-time migration marker: 0.1.0 shipped with profile default "acp". */
        var migratedProfile: Boolean = false
    }

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(state: Data) {
        data = state
        // 0.1.0 defaulted to the bridge-less "acp" profile; bridge features
        // (presets/permissions/titles/commands/skills/export) need acp-vscode.
        if (!data.migratedProfile && data.profile == "acp") {
            data.profile = "acp-vscode"
            data.migratedProfile = true
        }
    }

    var runtime: String
        get() = data.runtime
        set(v) { data.runtime = v }
    var command: String
        get() = data.command
        set(v) { data.command = v }
    var profile: String
        get() = data.profile
        set(v) { data.profile = v }
    var wslDistro: String
        get() = data.wslDistro
        set(v) { data.wslDistro = v }
    var env: String
        get() = data.env
        set(v) { data.env = v }

    fun envMap(): Map<String, String> =
        env.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            // Keys land in `export KEY=...` shell lines — reject anything that
            // is not a valid shell identifier (injection guard).
            .filterKeys { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }

    companion object {
        fun getInstance(): DshSettingsState =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}
