package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.SessionInfoData
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Empty state, mirroring the VS Code/web landing: centered orca, the workspace
 * name, and clickable recent sessions to resume.
 */
class WelcomePanel(
    private val project: Project,
    private val onResume: (SessionInfoData) -> Unit,
    private val onBrowseSessions: () -> Unit,
) : JPanel(BorderLayout()) {

    private val sessionsBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        val rawIcon = com.intellij.openapi.util.IconLoader.getIcon("/icons/welcome-orca.svg", WelcomePanel::class.java)
        val logo = JLabel(com.intellij.util.IconUtil.scale(rawIcon, null, 4.0f))
            .apply { alignmentX = CENTER_ALIGNMENT }
        logo.border = JBUI.Borders.empty(0, 0, 12, 0)

        val title = JBLabel("DeepSeek Harness").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size + 6f)
            alignmentX = CENTER_ALIGNMENT
        }
        val workspace = JBLabel(project.name).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(10, 0, 0, 0)
            alignmentX = CENTER_ALIGNMENT
        }

        content.add(logo)
        content.add(title)
        content.add(workspace)
        content.add(Box.createVerticalStrut(JBUI.scale(18)))
        content.add(sessionsBox)

        val center = JPanel(GridBagLayout())
        center.isOpaque = false
        center.add(content, GridBagConstraints())
        add(center, BorderLayout.CENTER)
    }

    fun setSessions(sessions: List<SessionInfoData>) {
        sessionsBox.removeAll()
        for (s in sessions.take(6)) {
            val label = s.title ?: DshBundle.message("sessions.untitled")
            val btn = JBLabel("💬  $label").apply {
                foreground = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(3, 12)
                alignmentX = CENTER_ALIGNMENT
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = onResume(s)
                })
            }
            sessionsBox.add(btn)
        }
        if (sessions.size > 6) {
            val more = JBLabel(DshBundle.message("welcome.more", sessions.size - 6)).apply {
                foreground = JBColor.GRAY
                alignmentX = CENTER_ALIGNMENT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = onBrowseSessions()
                })
            }
            sessionsBox.add(more)
        }
        if (sessions.isEmpty()) {
            sessionsBox.add(JBLabel(DshBundle.message("chat.noSessions")).apply {
                foreground = JBColor.GRAY
                alignmentX = CENTER_ALIGNMENT
            })
        }
        sessionsBox.revalidate()
        sessionsBox.repaint()
    }
}
