package com.dshlite.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * "@file" mention picker: filters project files by name substring as the user
 * types after '@'. Index access runs off the EDT (read actions in a document
 * listener would block the UI).
 */
class FileMentionPopup(
    private val project: Project,
    private val composerProvider: () -> javax.swing.JComponent,
    onPick: (FilePick) -> Unit,
) {
    data class FilePick(val name: String, val absPath: String)

    private val picker = PickerPopup<FilePick>(composerProvider, { pick -> onPick(pick) })
    private var querySeq = 0

    val isVisible: Boolean get() = picker.isVisible

    fun update(query: String) {
        if (query.isBlank()) {
            hide() // no empty-query sweep over 100k+ names; type at least one char
            return
        }
        val seq = ++querySeq
        ApplicationManager.getApplication().executeOnPooledThread {
            // ProjectFileIndex.iterateContent covers project content only —
            // FilenameIndex.getVirtualFilesByName with projectScope silently
            // returned nothing for the top name matches on the user's machine.
            val matches = runCatching {
                ReadAction.compute<List<FilePick>, RuntimeException> {
                    if (project.isDisposed) return@compute emptyList()
                    val out = ArrayList<FilePick>()
                    val q = query.lowercase()
                    com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
                        .iterateContent { vf ->
                            com.intellij.openapi.progress.ProgressManager.checkCanceled()
                            if (!vf.isDirectory && vf.name.lowercase().contains(q)) {
                                out.add(FilePick(vf.name, File(vf.path).absolutePath))
                            }
                            out.size < 50
                        }
                    out
                }
            }.getOrElse { e ->
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                com.intellij.openapi.diagnostic.Logger.getInstance(FileMentionPopup::class.java)
                    .warn("[dsh] file mention query failed: " + e.message)
                emptyList()
            }
            SwingUtilities.invokeLater {
                if (project.isDisposed || seq != querySeq) return@invokeLater // stale query
                if (matches.isEmpty()) picker.hide()
                else picker.show(matches.map {
                    PickerPopup.Row(value = it, label = it.name, detail = it.absPath)
                })
            }
        }
    }

    fun hide() {
        querySeq++
        picker.hide()
    }

    fun forwardKey(e: KeyEvent): Boolean = picker.forwardKey(e)
}
