package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Shared plain-text session label: lives as the first title action so the
        // IDE renders it without the setTitle() chip/border styling.
        val titleLabel = javax.swing.JLabel("🔴").apply {
            foreground = com.intellij.ui.JBColor.GRAY
            border = com.intellij.util.ui.JBUI.Borders.empty(0, 4, 0, 4)
        }
        val panel = ChatPanel(project, titleLabel)
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Title-bar actions: session label, + new session, sessions history
        // (right side, before the gear menu), mirroring the VS Code view title bar.
        toolWindow.setTitleActions(
            listOf(
                object : DumbAwareAction(), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
                    override fun createCustomComponent(
                        presentation: com.intellij.openapi.actionSystem.Presentation,
                        place: String,
                    ) = titleLabel
                    override fun actionPerformed(e: AnActionEvent) {}
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                object : DumbAwareAction(AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) = panel.createNewSessionAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
                object : DumbAwareAction(AllIcons.Vcs.History) {
                    override fun actionPerformed(e: AnActionEvent) = panel.openSessionsAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
            )
        )
        // Everything else folds into the header's gear (…) menu.
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup().apply {
                add(object : DumbAwareAction(DshBundle.message("chat.skills")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.showSkillsDialogAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
                add(object : DumbAwareAction(DshBundle.message("chat.export")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.exportSessionAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
                addSeparator()
                add(object : DumbAwareAction(DshBundle.message("chat.importWebConfig")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.importWebConfigAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
                add(object : DumbAwareAction(DshBundle.message("chat.reconnect")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.reconnectAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
                add(object : DumbAwareAction(DshBundle.message("chat.installBridge")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.installBridgeAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
                add(object : DumbAwareAction(DshBundle.message("chat.doctor")) {
                    override fun actionPerformed(e: AnActionEvent) = panel.runDoctorAction()
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
            }
        )
    }
}
