package com.dshlite.idea.ui

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Shared picker popup on platform JBPopupFactory popups (rounded, native
 * spacing). Two modes:
 *  - requestFocus=true (rail/permission): a native list popup with speed
 *    search, separators and non-selectable rows; keyboard nav is built-in.
 *  - requestFocus=false (slash/@ in the composer): a component popup that
 *    keeps typing in the composer; arrows/Enter/Esc are forwarded from it.
 *
 * A hidden/cancelled popup is NEVER re-shown (AbstractPopup is single-shot);
 * a fresh instance is created per opening.
 */
class PickerPopup<T>(
    private val anchorProvider: () -> javax.swing.JComponent,
    private val onPick: (T) -> Unit,
    private val requestFocus: Boolean = false,
    private val width: Int = 460,
    /** Anchor the popup's RIGHT edge to the anchor's right edge (for the lock). */
    private val alignRight: Boolean = false,
) {
    /** group orders rows and renders a separator; header rows are unused. */
    data class Row<T>(
        val value: T,
        val label: String,
        val detail: String? = null,
        val group: String? = null,
        val enabled: Boolean = true,
        val header: Boolean = false,
    )

    /* ---------- focus mode: platform list popup ---------- */

    private fun showListPopup(rows: List<Row<T>>) {
        val step = object : BaseListPopupStep<Row<T>>(null, rows) {
            override fun getTextFor(value: Row<T>): String = value.label
            override fun isSelectable(value: Row<T>?): Boolean =
                value != null && value.enabled && !value.header
            override fun getSeparatorAbove(value: Row<T>?): ListSeparator? {
                if (value?.group == null) return null
                val idx = rows.indexOf(value)
                val prev = rows.getOrNull(idx - 1)
                return if (prev?.group != value.group) ListSeparator(value.group) else null
            }
            override fun onChosen(selectedValue: Row<T>, finalChoice: Boolean): PopupStep<*>? {
                onPick(selectedValue.value)
                return FINAL_CHOICE
            }
        }
        val cur = currentValue
        if (cur != null) {
            val idx = rows.indexOfFirst { it.value == cur && it.enabled && !it.header }
            if (idx >= 0) step.setDefaultOptionIndex(idx)
        }
        val anchor = anchorProvider()
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        // Position ABOVE the anchor: showing at its north-west point grows the
        // popup DOWNWARD over the composer (wrong). We want the bottom edge at
        // the anchor's top, left-aligned — or right-aligned for the lock icon.
        val h = popup.content.preferredSize.height
        val w = popup.content.preferredSize.width
        val x = if (alignRight) anchor.width - w else 0
        popup.show(com.intellij.ui.awt.RelativePoint(anchor, java.awt.Point(x, -h)))
    }

    /* ---------- composer mode: component popup, keyboard forwarded ---------- */

    private val model = DefaultListModel<Row<T>>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<Row<T>>() {
            override fun customizeCellRenderer(
                l: JList<out Row<T>>, value: Row<T>?, index: Int,
                selected: Boolean, hasFocus: Boolean,
            ) {
                if (value == null) return
                append(
                    value.label,
                    if (value.enabled) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
                value.detail?.takeIf { it.isNotBlank() }?.let {
                    append("   " + it.take(120), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        border = JBUI.Borders.empty(4)
    }
    /** When set, the matching row is preselected instead of the first one. */
    var currentValue: String? = null

    private var popup: com.intellij.openapi.ui.popup.JBPopup? = null

    val isVisible: Boolean
        get() = if (requestFocus) false else popup?.isVisible == true

    fun show(rows: List<Row<T>>) {
        if (requestFocus) {
            showListPopup(rows)
            return
        }
        model.clear()
        rows.forEach(model::addElement)
        if (rows.isEmpty()) {
            hide()
            return
        }
        val cur = currentValue
        val curIdx = if (cur == null) -1 else rows.indexOfFirst { it.value == cur && it.enabled && !it.header }
        list.selectedIndex = when {
            curIdx >= 0 -> curIdx
            else -> rows.indexOfFirst { !it.header && it.enabled }.coerceAtLeast(0)
        }
        val anchor = anchorProvider()
        // Live-update while visible; rebuild when hidden (single-shot popup).
        val existing = popup
        if (existing != null && existing.isVisible) return
        // Native list popup WITHOUT focus stealing: component popups never
        // rendered on the user's Android Studio; list popups do.
        val p = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setRequestFocus(false)
            .setResizable(true)
            .setItemChosenCallback { row -> if (row.enabled && !row.header) onPick(row.value) }
            .createPopup()
        popup = p
        val x = if (alignRight) anchor.width - JBUI.scale(width) else 0
        p.content.preferredSize = JBUI.size(width, minOf(rows.size, 10) * JBUI.scale(24) + JBUI.scale(8))
        p.show(com.intellij.ui.awt.RelativePoint(anchor, java.awt.Point(x, -p.content.preferredSize.height)))
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    /** Forward a composer key event; true when consumed. */
    fun forwardKey(e: KeyEvent): Boolean {
        if (!isVisible) return false
        when (e.keyCode) {
            KeyEvent.VK_UP -> move(-1)
            KeyEvent.VK_DOWN -> move(1)
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> pick()
            KeyEvent.VK_ESCAPE -> hide()
            else -> return false
        }
        return true
    }

    private fun move(delta: Int) {
        val n = model.size()
        if (n == 0) return
        // Clamp, no wrap: Up on the first row and Down on the last are no-ops.
        var next = (list.selectedIndex + delta).coerceIn(0, n - 1)
        while (next > 0 && next < n - 1 && (model.get(next).header || !model.get(next).enabled)) next += delta
        if (model.get(next).header || !model.get(next).enabled) return
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun pick() {
        val row = list.selectedValue
        if (row != null && row.enabled && !row.header) onPick(row.value)
        hide()
    }
}
