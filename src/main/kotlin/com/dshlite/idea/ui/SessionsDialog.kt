package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.SessionInfoData
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Bridge-backed session management operations (null when the bridge is off). */
class SessionOps(
    val canTitle: Boolean,
    val canDelete: Boolean,
    val rename: (sessionId: String, title: String) -> CompletableFuture<*>,
    val delete: (sessionId: String) -> CompletableFuture<*>,
)

/** Picks a past session to resume; with bridge ops also rename/delete. */
class SessionsDialog(
    private val project: Project,
    sessions: List<SessionInfoData>,
    private val ops: SessionOps? = null,
    /** The session open in the chat panel: preselected and tagged in the list. */
    private val currentId: String? = null,
    /** Called after a rename/delete with a refresher that yields the new list. */
    private val onChanged: ((List<SessionInfoData>) -> Unit) -> Unit = {},
) : DialogWrapper(project, true) {

    private val model = DefaultListModel<SessionInfoData>()
    private val list = JBList(model)

    var selected: SessionInfoData? = null
        private set

    init {
        title = DshBundle.message("sessions.title")
        sessions.forEach(model::addElement)
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
                // Untitled rows are indistinguishable — fall back to the short id.
                val current = value.sessionId == currentId
                append(
                    value.title ?: value.sessionId.take(8),
                    if (current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
                if (current) append("  " + DshBundle.message("sessions.current"), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                value.updatedAt?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                value.cwd?.let { append("  $it", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES) }
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
        select(null)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(520, 320)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        if (ops == null) {
            panel.add(
                com.intellij.ui.components.JBLabel(DshBundle.message("sessions.bridgeOff"))
                    .apply { border = JBUI.Borders.empty(4, 4) },
                BorderLayout.SOUTH,
            )
        }
        return panel
    }

    override fun createLeftSideActions(): Array<Action> {
        val o = ops ?: return emptyArray()
        val rename = object : AbstractAction(DshBundle.message("sessions.rename")) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val sel = list.selectedValue ?: return
                val newTitle = Messages.showInputDialog(
                    project,
                    DshBundle.message("sessions.rename.prompt"),
                    DshBundle.message("sessions.rename"),
                    null,
                    sel.title ?: "",
                    null,
                )?.trim().takeUnless { it.isNullOrEmpty() } ?: return

                o.rename(sel.sessionId, newTitle).whenComplete { _, err ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed || !isShowing) return@invokeLater
                        if (err != null) {
                            Messages.showErrorDialog(project, err.message, DshBundle.message("sessions.rename"))
                        } else {
                            val idx = model.indexOf(sel)

                            if (idx >= 0) model.set(idx, sel.copy(title = newTitle))
                            list.repaint()

                            onChanged { fresh -> replaceAll(fresh) }
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }
        }
        val delete = object : AbstractAction(DshBundle.message("sessions.delete")) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val sel = list.selectedValue ?: return
                val rc = Messages.showYesNoDialog(
                    project,
                    DshBundle.message("sessions.delete.confirm", sel.title ?: sel.sessionId.take(8)),
                    DshBundle.message("sessions.delete"),
                    Messages.getWarningIcon(),
                )
                if (rc != Messages.YES) return

                o.delete(sel.sessionId).whenComplete { _, err ->
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed || !isShowing) return@invokeLater
                        if (err != null) {
                            val c = (err as? java.util.concurrent.ExecutionException)?.cause ?: err
                            Messages.showErrorDialog(project, c.message, DshBundle.message("sessions.delete"))
                        } else {
                            model.removeElement(sel)
                            select(null)
                            list.repaint()

                            onChanged { fresh -> replaceAll(fresh) }
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }
        }
        rename.isEnabled = o.canTitle
        delete.isEnabled = o.canDelete
        return arrayOf(rename, delete)
    }

    private fun replaceAll(fresh: List<SessionInfoData>) {
        if (project.isDisposed || !isShowing) return
        val keep = list.selectedValue?.sessionId
        model.clear()
        fresh.forEach(model::addElement)
        select(keep)
    }

    /** Select [preferred], else the current session, else the first row. */
    private fun select(preferred: String?) {
        if (model.isEmpty) {
            list.clearSelection()
            return
        }
        val ids = (0 until model.size()).map { model[it].sessionId }
        val idx = sessionSelectionIndex(ids, preferred, currentId)
        list.selectedIndex = idx
        list.ensureIndexIsVisible(idx)
    }

    override fun doOKAction() {
        selected = list.selectedValue
        super.doOKAction()
    }
}

/** Preserve the selection on refresh, falling back to the open session. */
internal fun sessionSelectionIndex(ids: List<String>, preferred: String?, current: String?): Int {
    if (ids.isEmpty()) return -1
    return listOf(preferred, current).firstNotNullOfOrNull { id ->
        id?.let(ids::indexOf)?.takeIf { it >= 0 }
    } ?: 0
}
