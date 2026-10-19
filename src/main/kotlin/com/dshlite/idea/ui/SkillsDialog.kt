package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

data class SkillInfo(val name: String, val description: String?, val whenToUse: String?)

/** Browse the bridge skill catalog; OK inserts "/<name> " into the composer. */
class SkillsDialog(
    project: Project,
    skills: List<SkillInfo>,
) : DialogWrapper(project, true) {

    private val model = DefaultListModel<SkillInfo>()
    private val list = JBList(model)

    var selected: SkillInfo? = null
        private set

    init {
        title = DshBundle.message("skills.title")
        setOKButtonText(DshBundle.message("skills.use"))
        skills.forEach(model::addElement)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<SkillInfo>() {
            override fun customizeCellRenderer(
                l: JList<out SkillInfo>, value: SkillInfo?, index: Int,
                selected: Boolean, hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                (value.description ?: value.whenToUse)?.takeIf { it.isNotBlank() }?.let {
                    append("   " + it.take(120), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
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
        if (skills.isNotEmpty()) list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(560, 360)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        selected = list.selectedValue
        super.doOKAction()
    }
}
