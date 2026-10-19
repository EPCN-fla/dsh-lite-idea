package com.dshlite.idea.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.text.JTextComponent

/**
 * Height-for-width sizing. Swing's preferred sizes are width-blind, which is
 * why wrapped text either never wraps or wraps at a stale width; every
 * component in the transcript reports its height for a GIVEN width instead.
 */
interface HeightForWidth {
    fun heightForWidth(width: Int): Int
}

/** Height of [c] when laid out [width] pixels wide. */
fun heightForWidth(c: Component, width: Int): Int = when {
    !c.isVisible -> 0
    c is HeightForWidth -> c.heightForWidth(width)
    c is JTextComponent -> {
        // BasicTextUI sizes its root view from the current bounds, but only
        // when both dimensions are positive — give it a tall probe box.
        if (c.width != width || c.height <= 0) c.setSize(width, Short.MAX_VALUE.toInt())
        c.preferredSize.height
    }
    else -> c.preferredSize.height
}

/**
 * Vertical stack: every child gets the full inner width and its
 * height-for-width. The building block of all transcript widgets.
 */
open class VStack(private val gap: Int = 0) : JPanel(), HeightForWidth {
    init {
        isOpaque = false
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: Component?) {}
            override fun removeLayoutComponent(comp: Component?) {}
            override fun minimumLayoutSize(parent: Container) = Dimension(0, 0)
            override fun preferredLayoutSize(parent: Container): Dimension {
                val w = if (parent.width > 0) parent.width else JBUI.scale(300)
                return Dimension(w, heightForWidth(w))
            }
            override fun layoutContainer(parent: Container) {
                val ins = parent.insets
                val w = (parent.width - ins.left - ins.right).coerceAtLeast(0)
                var y = ins.top
                for (c in parent.components) {
                    if (!c.isVisible) continue
                    val h = heightForWidth(c, w)
                    c.setBounds(ins.left, y, w, h)
                    y += h + gap
                }
            }
        }
    }

    override fun heightForWidth(width: Int): Int {
        val ins: Insets = insets
        val inner = (width - ins.left - ins.right).coerceAtLeast(0)
        var h = 0
        var n = 0
        for (c in components) {
            if (!c.isVisible) continue
            h += heightForWidth(c, inner)
            n++
        }
        if (n > 1) h += gap * (n - 1)
        return h + ins.top + ins.bottom
    }
}

/**
 * The transcript: a [Scrollable] that tracks the viewport WIDTH (so text
 * wraps to the window) but not its height (so it grows and the wheel scrolls).
 * Row heights are cached per width and dropped whenever a row is invalidated
 * (any content change revalidates up through its row), so streaming into the
 * last message does not re-measure the whole history.
 */
class MessageList : JPanel(), Scrollable {
    private val gap = JBUI.scale(10)
    private val cache = HashMap<Component, Long>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 12, 10)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: Component?) {}
            override fun removeLayoutComponent(comp: Component?) { cache.remove(comp) }
            override fun minimumLayoutSize(parent: Container) = Dimension(0, 0)
            override fun preferredLayoutSize(parent: Container): Dimension {
                val outer = viewportWidth()
                val ins = insets
                val inner = (outer - ins.left - ins.right).coerceAtLeast(0)
                var h = ins.top + ins.bottom
                var n = 0
                for (c in components) {
                    if (!c.isVisible) continue
                    h += measure(c, inner)
                    n++
                }
                if (n > 1) h += gap * (n - 1)
                return Dimension(outer, h)
            }
            override fun layoutContainer(parent: Container) {
                val ins = insets
                val inner = (width - ins.left - ins.right).coerceAtLeast(0)
                var y = ins.top
                for (c in components) {
                    if (!c.isVisible) continue
                    val h = measure(c, inner)
                    c.setBounds(ins.left, y, inner, h)
                    y += h + gap
                }
            }
        }
    }

    private fun viewportWidth(): Int =
        (parent as? JViewport)?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: JBUI.scale(400)

    private fun measure(c: Component, w: Int): Int {
        val key = cache[c]
        if (key != null && (key ushr 32).toInt() == w && c is Row && !c.dirty) return key.toInt()
        val h = heightForWidth(c, w)
        if (c is Row) {
            c.dirty = false
            cache[c] = (w.toLong() shl 32) or (h.toLong() and 0xffffffffL)
        }
        return h
    }

    // ViewportLayout asks for the preferred height before laying out the
    // view at its new width. Container's preferred-size cache can still hold
    // the old height: compute it against the current viewport width instead.
    override fun getPreferredSize(): Dimension = layout.preferredLayoutSize(this)

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) (visibleRect.height - JBUI.scale(24)).coerceAtLeast(JBUI.scale(24))
        else visibleRect.width
    override fun getScrollableTracksViewportWidth() = true
    // Fill a short transcript's viewport (clicks below the last row still land here).
    override fun getScrollableTracksViewportHeight(): Boolean {
        val vp = parent as? JViewport ?: return false
        return vp.height > preferredSize.height
    }

    /**
     * A top-level transcript row. Any revalidation inside it marks its cached
     * height stale; plain resizes by the list (same width) keep the cache.
     */
    open class Row(gap: Int = 0) : VStack(gap) {
        var dirty = true
        override fun invalidate() {
            super.invalidate()
            dirty = true
        }
        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            val keep = !dirty && width == this.width
            super.setBounds(x, y, width, height)
            if (keep) dirty = false
        }
    }
}

