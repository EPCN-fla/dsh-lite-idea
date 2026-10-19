package com.dshlite.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

/**
 * Circular send/stop button embedded at the composer rail's right end,
 * mirroring the dsh Web / VS Code composer. Glyphs are drawn directly
 * (no icon recoloring needed): a right-pointing arrow for send, a square
 * for stop.
 */
class RoundButton(
    private val accent: Color = JBColor.namedColor("Link.activeForeground", JBColor.BLUE),
) : JButton() {

    enum class Glyph { SEND, STOP }

    var glyph: Glyph = Glyph.SEND
        set(v) { field = v; repaint() }

    init {
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = minOf(width, height) - JBUI.scale(4)
            val x = (width - d) / 2
            val y = (height - d) / 2
            g2.color = when {
                !isEnabled -> JBColor.GRAY
                model.isPressed -> accent.darker()
                model.isRollover -> accent.brighter()
                else -> accent
            }
            g2.fillOval(x, y, d, d)
            g2.color = Color.WHITE
            val s = JBUI.scale(5) // glyph half-size
            val cx = width / 2
            val cy = height / 2
            when (glyph) {
                Glyph.SEND -> g2.fillPolygon(
                    intArrayOf(cx - s + JBUI.scale(1), cx - s + JBUI.scale(1), cx + s + JBUI.scale(1)),
                    intArrayOf(cy - s, cy + s, cy),
                    3,
                )
                Glyph.STOP -> {
                    val r = s - JBUI.scale(1)
                    g2.fillRoundRect(cx - r, cy - r, r * 2, r * 2, JBUI.scale(2), JBUI.scale(2))
                }
            }
        } finally {
            g2.dispose()
        }
    }
}
