package com.dshlite.idea.settings

import com.dshlite.idea.DshBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class DshConfigurable : Configurable {

    private val runtime = JComboBox(arrayOf("auto", "wsl", "windows"))
    private val command = JBTextField()
    private val profile = JBTextField()
    private val wslDistro = JBTextField()
    private val env = JBTextArea(4, 40)
    private var panel: JPanel? = null

    override fun getDisplayName(): String = DshBundle.message("settings.title.full")

    override fun createComponent(): JComponent {
        env.lineWrap = true
        env.toolTipText = DshBundle.message("settings.env.tooltip")
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(DshBundle.message("settings.runtime"), runtime)
            .addLabeledComponent(DshBundle.message("settings.command"), command)
            .addLabeledComponent(DshBundle.message("settings.profile"), profile)
            .addLabeledComponent(DshBundle.message("settings.wslDistro"), wslDistro)
            .addLabeledComponent(DshBundle.message("settings.env"), env)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(8)
        return JPanel(BorderLayout()).also { it.add(form, BorderLayout.NORTH); panel = it }
    }

    override fun isModified(): Boolean {
        val s = DshSettingsState.getInstance()
        return runtime.selectedItem as String != s.runtime ||
            command.text.trim() != s.command ||
            profile.text.trim() != s.profile ||
            wslDistro.text.trim() != s.wslDistro ||
            env.text != s.env
    }

    override fun apply() {
        val s = DshSettingsState.getInstance()
        s.runtime = runtime.selectedItem as String
        s.command = command.text.trim().ifBlank { "npx -y @deepseek-ai/dsh" }
        s.profile = profile.text.trim().ifBlank { "acp-vscode" }
        s.wslDistro = wslDistro.text.trim()
        s.env = env.text
    }

    override fun reset() {
        val s = DshSettingsState.getInstance()
        runtime.selectedItem = when (s.runtime) {
            "local" -> if (com.intellij.openapi.util.SystemInfoRt.isWindows) "windows" else "auto"
            else -> s.runtime
        }
        command.text = s.command
        profile.text = s.profile
        wslDistro.text = s.wslDistro
        env.text = s.env
    }

    override fun disposeUIResources() { panel = null }
}
