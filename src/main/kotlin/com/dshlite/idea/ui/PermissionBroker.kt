package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.PermissionRequestData
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.CompletableFuture

object PermissionBroker {
    /**
     * Show a modal permission dialog on the EDT and complete with the selected
     * optionId, or null when dismissed/cancelled.
     */
    fun ask(project: Project, request: PermissionRequestData): CompletableFuture<String?> {
        val future = CompletableFuture<String?>()
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            // A disposed project means nobody will answer — settle the agent's
            // pending permission request instead of letting it hang.
            if (project.isDisposed) {
                future.complete(null)
                return@invokeLater
            }
            try {
                val detail = buildString {
                    append(request.title)
                    request.kind?.let { append("\n").append(DshBundle.message("permission.kind", it)) }
                    request.rawInput?.let { raw ->
                        val s = raw.toString()
                        if (s.isNotBlank() && s != "null") {
                            append("\n\n").append(if (s.length > 600) s.take(600) + "…" else s)
                        }
                    }
                }
                if (request.options.isEmpty()) {
                    Messages.showInfoMessage(project, detail, DshBundle.message("permission.title"))
                    future.complete(null)
                    return@invokeLater
                }
                val labels = request.options.map { it.name }.toTypedArray()
                val defaultIndex = request.options.indexOfFirst { it.kind.startsWith("allow") }
                    .takeIf { it >= 0 } ?: 0
                val idx = Messages.showDialog(
                    project,
                    detail,
                    DshBundle.message("permission.title"),
                    labels,
                    defaultIndex,
                    Messages.getWarningIcon(),
                )
                future.complete(if (idx in request.options.indices) request.options[idx].optionId else null)
            } catch (e: Exception) {
                future.complete(null)
            }
        }
        return future
    }
}
