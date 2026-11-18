package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.chat.ChangedFile
import com.dshlite.idea.chat.ChangedFileKind
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane

/** Collapsible per-session changed-file summary directly above the composer. */
class ChangesBar(private val onOpen: (ChangedFile) -> Unit) : JPanel(BorderLayout()) {
    var files: List<ChangedFile> = emptyList()
        private set
    private var expanded = false
    private val rows = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val toggle = JButton().apply {
        putClientProperty("html.disable", true)
        isContentAreaFilled = false
        isBorderPainted = false
        horizontalAlignment = JButton.LEFT
        border = JBUI.Borders.empty(4, 8)
        addActionListener {
            expanded = !expanded
            updatePresentation()
        }
    }
    private val listScroll = JBScrollPane(rows).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    init {
        isOpaque = false
        add(toggle, BorderLayout.NORTH)
        add(listScroll, BorderLayout.CENTER)
        isVisible = false
        listScroll.isVisible = false
    }

    fun setFiles(value: List<ChangedFile>) {
        files = value
        rows.removeAll()
        for (file in value) {
            val created = file.kind == ChangedFileKind.CREATED
            val marker = if (created) "A" else "M"
            val button = JButton().apply {
                // Set this before any untrusted filename becomes button text.
                putClientProperty("html.disable", true)
                text = "$marker  ${file.label}   ${DshBundle.message("changes.openDiff")}"
                toolTipText = if (file.path.startsWith("<html>", ignoreCase = true)) " ${file.path}" else file.path
                isContentAreaFilled = false
                isBorderPainted = false
                horizontalAlignment = JButton.LEFT
                border = JBUI.Borders.empty(3, 12)
                foreground = if (created) JBColor(0x38761D, 0x73C991) else JBColor(0x9C6500, 0xE2C08D)
                addActionListener { onOpen(file) }
            }
            rows.add(button)
        }
        updatePresentation()
    }

    private fun updatePresentation() {
        val created = files.count { it.kind == ChangedFileKind.CREATED }
        val modified = files.size - created
        toggle.text = (if (expanded) "▾ " else "▸ ") + DshBundle.message("changes.summary", files.size, created, modified)
        isVisible = files.isNotEmpty()
        listScroll.isVisible = expanded && files.isNotEmpty()
        listScroll.preferredSize = Dimension(0, JBUI.scale((files.size * 28 + 8).coerceIn(36, 176)))
        rows.revalidate()
        rows.repaint()
        revalidate()
        repaint()
        parent?.revalidate()
    }
}
