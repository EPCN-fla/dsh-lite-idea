package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.SessionInfoData
import com.dshlite.idea.chat.HistoryMessage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Direct-child picker. Selecting a row never resumes or closes an ACP session. */
class SubagentsDialog(
    project: Project,
    rows: List<SessionInfoData>,
) : DialogWrapper(project, true) {
    private val model = DefaultListModel<SessionInfoData>()
    private val list = JBList(model)
    var selected: SessionInfoData? = null
        private set

    init {
        title = DshBundle.message("subagents.title")
        rows.forEach(model::addElement)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<SessionInfoData>() {
            override fun customizeCellRenderer(
                l: javax.swing.JList<out SessionInfoData>,
                value: SessionInfoData?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(if (value.live) "● " else "○ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.title ?: value.sessionId.take(8))
                value.updatedAt?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) {
                    selected = list.selectedValue
                    close(OK_EXIT_CODE)
                }
            }
        })
        if (!model.isEmpty) list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = JBUI.size(520, 300)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JBLabel(DshBundle.message("subagents.readonly")).apply {
            border = JBUI.Borders.empty(6, 4)
        }, BorderLayout.SOUTH)
    }

    override fun doOKAction() {
        selected = list.selectedValue
        super.doOKAction()
    }
}

/** Independent read-only transcript; the main chat and composer stay untouched. */
class SubagentTranscriptDialog(
    project: Project,
    child: SessionInfoData,
) : DialogWrapper(project, true) {
    private val messages = MessageList()
    private val status = JBLabel(DshBundle.message("subagents.loading"))

    init {
        title = DshBundle.message("subagents.transcript.title", child.title ?: child.sessionId.take(8))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = JBUI.size(720, 520)
        add(status.apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
        add(JBScrollPane(messages).apply {
            horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(
        object : javax.swing.AbstractAction(DshBundle.message("subagents.back")) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = close(OK_EXIT_CODE)
        },
    )

    fun showError(message: String) {
        status.text = message
        status.isVisible = true
    }

    fun setMessages(items: List<HistoryMessage>) {
        messages.removeAll()
        for (item in items) {
            val view = when (item) {
                is HistoryMessage.User -> UserMessageRow(item.text)
                is HistoryMessage.Assistant -> AssistantMessageRow().apply { setMarkdown(item.text) }
                is HistoryMessage.Thought -> ThoughtCard().apply { setText(item.text) }
                is HistoryMessage.Tool -> ToolCard(ToolCard.ToolState(
                    title = item.title,
                    subtitle = item.subtitle,
                    status = item.status,
                    input = item.input,
                    detail = item.detail,
                ))
            }
            messages.add(view)
        }
        status.text = if (items.isEmpty()) DshBundle.message("subagents.empty") else
            DshBundle.message("subagents.snapshot", items.size)
        status.isVisible = true
        messages.revalidate()
        messages.repaint()
    }
}
