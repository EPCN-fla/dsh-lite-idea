package com.dshlite.idea.ui

import java.awt.Component
import java.awt.event.KeyEvent

/** Slash-command picker backed by the shared platform popup. */
class SlashPopup(
    private val anchorProvider: () -> javax.swing.JComponent,
    onPick: (SlashCommand) -> Unit,
) {
    private val picker = PickerPopup<SlashCommand>(anchorProvider, { cmd -> onPick(cmd) })

    val isVisible: Boolean get() = picker.isVisible

    fun show(anchor: Component, commands: List<SlashCommand>) {
        picker.show(commands.map {
            PickerPopup.Row(
                value = it,
                label = "/" + it.name + (if (it.label != it.name) "  " + it.label else ""),
                detail = it.description.takeIf { d -> d.isNotBlank() },
                group = if (it.section == SlashCommand.Section.SKILL) "技能" else "指令",
            )
        })
    }

    fun hide() = picker.hide()
    fun forwardKey(e: KeyEvent): Boolean = picker.forwardKey(e)
}
