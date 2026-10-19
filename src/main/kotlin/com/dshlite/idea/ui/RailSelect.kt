package com.dshlite.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Flat text+ chevron selector for the composer rail, mirroring the dsh
 * Web / VS Code look: transparent background, hover color block, popup menu
 * with optional gray section headers and disabled rows.
 */
class RailSelect(tooltip: String) : JComponent() {

    data class Item(
        val value: String,
        val label: String,
        val group: String? = null,
        val enabled: Boolean = true,
        val hint: String? = null,
    )

    var items: List<Item> = emptyList()
    var selectedValue: String? = null
    var onSelect: (String) -> Unit = {}
    /** Text when nothing is selected. */
    var placeholder: String = "—"

    private var hover = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.smallFont()
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { if (isEnabled) openPopup() }
        })
    }

    private fun displayText(): String {
        val sel = selectedValue
        return (items.firstOrNull { it.value == sel }?.label ?: placeholder) + " ▾"
    }

    override fun getPreferredSize(): Dimension {
        val fm: FontMetrics = getFontMetrics(font)
        return Dimension(fm.stringWidth(displayText()) + JBUI.scale(9), JBUI.scale(20))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hover && isEnabled) {
                g2.color = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xE9EEF2, 0x3D4145))
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            }
            g2.font = font
            g2.color = if (isEnabled) {
                JBColor.namedColor("ActionButton.foreground", JBColor.namedColor("Label.foreground", JBColor.GRAY))
            } else {
                JBColor.GRAY
            }
            val fm = g2.fontMetrics
            g2.drawString(displayText(), JBUI.scale(5), (height + fm.ascent - fm.descent) / 2)
        } finally {
            g2.dispose()
        }
    }

    fun openPopup() {
        val picker = PickerPopup<String>(
            anchorProvider = { this },
            onPick = { v -> if (v != selectedValue) onSelect(v) },
            requestFocus = true,
        )
        picker.currentValue = selectedValue
        picker.show(items.map {
            PickerPopup.Row(
                value = it.value,
                label = it.label,
                group = it.group,
                enabled = it.enabled,
            )
        })
    }
}
