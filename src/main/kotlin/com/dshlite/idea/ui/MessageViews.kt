package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Read-only wrapped (selectable) text, sized height-for-width by the
 * transcript layout. Plain JTextArea: JBTextArea's extras cost ~3x per row.
 */
class WrapText(text: String = "", font: Font? = null) : javax.swing.JTextArea(text) {
    init {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        foreground = ChatColors.foreground
        this.font = font ?: ChatFonts.forText(text)
    }

    /** Unwrapped width of the longest line. */
    fun naturalWidth(): Int {
        val fm = getFontMetrics(font)
        val longest = text.lineSequence().maxOfOrNull { fm.stringWidth(it.replace("\t", "    ")) } ?: 0
        val ins = insets
        return longest + ins.left + ins.right + JBUI.scale(2)
    }
}

/** Panel painting a rounded fill and/or outline behind a vertical stack. */
open class RoundedBox(
    gap: Int = 0,
    private val fill: () -> Color? = { null },
    private val outline: () -> Color? = { ChatColors.border },
    private val arc: Int = JBUI.scale(10),
) : VStack(gap) {
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fill()?.let {
                g2.color = it
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            outline()?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** Small icon button (copy, etc.) with a rounded hover highlight. */
open class IconButton(icon: Icon, tooltip: String, private val onClick: (IconButton) -> Unit) : JLabel(icon) {
    private var hover = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3)
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { onClick(this@IconButton) }
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hover) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ChatColors.hover
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** Copy-to-clipboard icon; flips to a check mark for a moment after copying. */
class CopyButton(getText: () -> String) : IconButton(AllIcons.Actions.Copy, DshBundle.message("chat.copy"), { b ->
    com.intellij.openapi.ide.CopyPasteManager.getInstance()
        .setContents(java.awt.datatransfer.StringSelection(getText()))
    b.icon = AllIcons.Actions.Checked
    javax.swing.Timer(1200) { b.icon = AllIcons.Actions.Copy }.apply { isRepeats = false; start() }
})

/** Horizontal strip of action icons (fixed height). */
fun actionStrip(align: Int, vararg items: JComponent): JPanel =
    JPanel(FlowLayout(align, 0, 0)).apply {
        isOpaque = false
        items.forEach { add(it) }
    }

/**
 * A text block that shrinks to its content and is aligned within the row:
 * the user bubble (right, max 85%) and centered system notes.
 */
class FitText(
    val text: WrapText,
    private val align: Int,
    private val maxFraction: Double,
    private val box: JComponent,
) : JPanel(), HeightForWidth {
    init {
        isOpaque = false
        add(box)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: Component?) {}
            override fun removeLayoutComponent(comp: Component?) {}
            override fun minimumLayoutSize(parent: Container) = Dimension(0, 0)
            override fun preferredLayoutSize(parent: Container) = Dimension(parent.width, heightForWidth(parent.width))
            override fun layoutContainer(parent: Container) {
                val w = boxWidth(parent.width)
                val x = when (align) {
                    SwingConstants.RIGHT -> parent.width - w
                    SwingConstants.CENTER -> (parent.width - w) / 2
                    else -> 0
                }
                box.setBounds(x, 0, w, heightForWidth(box, w))
            }
        }
    }

    private fun boxWidth(width: Int): Int {
        val chrome = box.insets.left + box.insets.right
        val cap = (width * maxFraction).toInt()
        return (text.naturalWidth() + chrome).coerceAtMost(cap).coerceAtLeast(JBUI.scale(24).coerceAtMost(width))
    }

    override fun heightForWidth(width: Int): Int = heightForWidth(box, boxWidth(width))
}

/** User message: rounded bordered bubble on the right, copy icon beneath. */
class UserMessageRow(initial: String) : MessageList.Row(JBUI.scale(2)) {
    var message: String = initial
        private set
    private val text = WrapText(initial)

    init {
        val bubble = RoundedBox(fill = { ChatColors.bubble }, outline = { ChatColors.bubbleBorder }, arc = JBUI.scale(16)).apply {
            border = JBUI.Borders.empty(7, 12)
            add(text)
        }
        add(FitText(text, SwingConstants.RIGHT, 0.85, bubble))
        add(actionStrip(FlowLayout.RIGHT, CopyButton { message }))
    }

    fun setText(t: String) {
        message = t
        text.font = ChatFonts.forText(t)
        text.text = t
    }
}

/** Assistant reply: full-width markdown, copy icon beneath (left). */
class AssistantMessageRow : MessageList.Row(JBUI.scale(2)) {
    val view = MarkdownRenderer.View()
    private val strip = actionStrip(FlowLayout.LEFT, CopyButton { markdown }).apply { isVisible = false }
    var markdown: String = ""
        private set

    init {
        add(view)
        add(strip)
    }

    fun setMarkdown(md: String) {
        markdown = md
        view.setMarkdown(md)
        strip.isVisible = md.isNotBlank()
    }
}

/** Centered muted note (status lines such as "Resuming session…"). */
class SystemMessageRow(message: String) : MessageList.Row() {
    init {
        val text = WrapText(message, ChatFonts.forText(message, ChatFonts.small().deriveFont(Font.ITALIC))).apply {
            foreground = ChatColors.muted
        }
        add(FitText(text, SwingConstants.CENTER, 1.0, VStack().apply { add(text) }))
    }
}

/** Error box (tinted background + border). */
class ErrorMessageRow(message: String) : MessageList.Row() {
    init {
        val box = RoundedBox(fill = { ChatColors.errorBackground }, outline = { ChatColors.errorBorder }, arc = JBUI.scale(8)).apply {
            border = JBUI.Borders.empty(6, 10)
            add(WrapText(message))
        }
        add(box)
    }
}

/**
 * Collapsible card (VS Code `<details class="msg tool|thought">`): a clickable
 * header with a chevron, and a body built lazily on first expand.
 */
abstract class CollapsibleCard : MessageList.Row() {
    private val chevron = JLabel(AllIcons.General.ArrowRight)
    protected val leading = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    protected val titleLabel = JLabel()
    protected val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
    val body = VStack(JBUI.scale(4)).apply {
        border = JBUI.Borders.empty(2, 22, 6, 10)
        isVisible = false
    }
    private var built = false
    val expanded get() = body.isVisible

    init {
        leading.add(chevron)
        val header = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6, 4, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(leading, BorderLayout.WEST)
            add(titleLabel, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
        }
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        })
        val card = RoundedBox(arc = JBUI.scale(8)).apply {
            add(header)
            add(body)
        }
        add(card)
    }

    /** Fill [body] from the current state; called on expand and on updates while open. */
    protected abstract fun buildBody(body: VStack)

    fun toggle() {
        val open = !body.isVisible
        chevron.icon = if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (open && !built) rebuild()
        body.isVisible = open
        revalidate()
        repaint()
    }

    /** Re-create the body from the current state (no-op until first expanded). */
    fun rebuild() {
        built = true
        body.removeAll()
        buildBody(body)
        body.revalidate()
        body.repaint()
    }

    fun refreshIfOpen() {
        if (body.isVisible) rebuild() else built = false
    }
}

/** Collapsible "Thinking…" card; the body renders the reasoning as markdown. */
class ThoughtCard : CollapsibleCard() {
    private var text = ""
    private val view by lazy { MarkdownRenderer.View(muted = true) }

    init {
        titleLabel.text = DshBundle.message("chat.thinking")
        titleLabel.foreground = ChatColors.muted
        titleLabel.font = ChatFonts.small().deriveFont(Font.ITALIC)
    }

    // Rendered lazily: hidden reasoning can be long and streams constantly.
    override fun buildBody(body: VStack) {
        view.setMarkdown(text)
        body.add(view)
    }

    fun setText(t: String) {
        text = t
        if (expanded) view.setMarkdown(t)
    }

}

/** Status glyph for a tool call (✔ / ✖ / ● / ○). */
class StatusDot : JLabel() {
    fun set(status: String?) {
        when (status) {
            "completed" -> { text = "✔"; foreground = ChatColors.success }
            "failed" -> { text = "✖"; foreground = ChatColors.failure }
            "in_progress" -> { text = "●"; foreground = ChatColors.running }
            else -> { text = "○"; foreground = ChatColors.muted }
        }
    }
}

/** Two-column key/value table: muted keys, wrapped values. */
class KeyValueTable(rows: List<Pair<String, String>>) : JPanel(), HeightForWidth {
    private val keys = rows.map { (k, _) -> JLabel(k).apply { foreground = ChatColors.muted; font = ChatFonts.mono() } }
    private val values = rows.map { (_, v) -> WrapText(v.take(MAX_CELL), ChatFonts.forText(v, ChatFonts.mono())) }
    private val colGap = JBUI.scale(10)
    private val rowGap = JBUI.scale(2)

    init {
        isOpaque = false
        keys.forEach(::add)
        values.forEach(::add)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: Component?) {}
            override fun removeLayoutComponent(comp: Component?) {}
            override fun minimumLayoutSize(parent: Container) = Dimension(0, 0)
            override fun preferredLayoutSize(parent: Container) = Dimension(parent.width, heightForWidth(parent.width))
            override fun layoutContainer(parent: Container) {
                val kw = keyWidth(parent.width)
                val vw = (parent.width - kw - colGap).coerceAtLeast(1)
                var y = 0
                for (i in keys.indices) {
                    val h = maxOf(keys[i].preferredSize.height, heightForWidth(values[i], vw))
                    keys[i].setBounds(0, y, kw, keys[i].preferredSize.height)
                    values[i].setBounds(kw + colGap, y, vw, h)
                    y += h + rowGap
                }
            }
        }
    }

    private fun keyWidth(width: Int) =
        (keys.maxOfOrNull { it.preferredSize.width } ?: 0).coerceAtMost((width * 0.35).toInt())

    override fun heightForWidth(width: Int): Int {
        val kw = keyWidth(width)
        val vw = (width - kw - colGap).coerceAtLeast(1)
        var h = 0
        for (i in keys.indices) h += maxOf(keys[i].preferredSize.height, heightForWidth(values[i], vw)) + rowGap
        return (h - rowGap).coerceAtLeast(0)
    }

    companion object { const val MAX_CELL = 4000 }
}

/** Section label inside a tool card ("INPUT", "RESULT", "OUTPUT"). */
fun sectionLabel(text: String): JLabel = JLabel(text.uppercase()).apply {
    foreground = ChatColors.muted
    font = ChatFonts.small(delta = 3f)
    border = JBUI.Borders.emptyTop(2)
}

/** Monospace pre block on a faint code background (wrapped, bounded). */
fun preBlock(text: String): JComponent {
    val shown = if (text.length > MAX_PRE) text.take(MAX_PRE) + "\n… (truncated)" else text
    return RoundedBox(fill = { ChatColors.codeBackground }, outline = { null }, arc = JBUI.scale(6)).apply {
        border = JBUI.Borders.empty(4, 8)
        add(WrapText(shown, ChatFonts.forText(shown, ChatFonts.mono())).apply { wrapStyleWord = false })
    }
}

private const val MAX_PRE = 20_000

/** Collapsible tool-call card (VS Code `.msg.tool`). */
class ToolCard(var state: ToolState) : CollapsibleCard() {
    data class ToolState(
        var title: String,
        var subtitle: String? = null,
        var kind: String? = null,
        var status: String? = null,
        var input: com.google.gson.JsonElement? = null,
        var output: com.google.gson.JsonElement? = null,
        var detail: String? = null,
    )

    private val dot = StatusDot()
    private val nameLabel = JLabel()
    private val kindLabel = JLabel().apply { foreground = ChatColors.muted; font = smaller() }
    private val statusLabel = JLabel().apply { foreground = ChatColors.muted; font = smaller() }

    private fun smaller(): Font = ChatFonts.small(delta = 2f)

    init {
        leading.add(dot)
        leading.add(nameLabel)
        titleLabel.foreground = ChatColors.foreground
        trailing.add(kindLabel)
        trailing.add(statusLabel)
        refreshHeader()
    }

    fun update(mutate: ToolState.() -> Unit) {
        state.mutate()
        refreshHeader()
        refreshIfOpen()
        revalidate()
        repaint()
    }

    private fun refreshHeader() {
        dot.set(state.status)
        // Plain labels (no <html>): an HTML JLabel costs ~1.5ms to build, and a
        // long transcript has hundreds of tool rows. The subtitle truncates.
        nameLabel.text = state.title
        nameLabel.font = ChatFonts.forText(state.title, ChatFonts.small()).deriveFont(Font.BOLD)
        val sub = state.subtitle ?: ""
        titleLabel.text = sub
        titleLabel.font = ChatFonts.forText(sub, ChatFonts.small())
        titleLabel.toolTipText = if (sub.isEmpty()) null else sub
        kindLabel.text = state.kind?.takeIf { it != "other" } ?: ""
        kindLabel.isVisible = kindLabel.text.isNotEmpty()
        statusLabel.text = state.status ?: ""
    }

    /** Populate the body: input (k/v rows or verbatim), result, output. */
    override fun buildBody(body: VStack) {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        fun asText(e: com.google.gson.JsonElement): String =
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else gson.toJson(e)
        val input = state.input
        if (input != null && !input.isJsonNull) {
            body.add(sectionLabel("input"))
            if (input.isJsonObject && input.asJsonObject.size() > 0) {
                body.add(KeyValueTable(input.asJsonObject.entrySet().map { (k, v) -> k to asText(v) }))
            } else {
                body.add(preBlock(asText(input)))
            }
        }
        state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            body.add(sectionLabel("result"))
            val kv = com.dshlite.idea.chat.ToolText.xmlRows(detail)
            body.add(if (kv != null) KeyValueTable(kv) else preBlock(detail))
        }
        state.output?.takeIf { !it.isJsonNull }?.let {
            body.add(sectionLabel("output"))
            body.add(preBlock(asText(it)))
        }
        if (body.componentCount == 0) body.add(sectionLabel("no details"))
    }
}

/** Pinned-style task list card (plan updates replace its items in place). */
class TodoCard : MessageList.Row() {
    private val items = VStack(JBUI.scale(1))

    init {
        val box = RoundedBox(gap = JBUI.scale(4), arc = JBUI.scale(8)).apply {
            border = JBUI.Borders.empty(6, 10)
            add(JLabel(DshBundle.message("chat.tasks")).apply {
                foreground = ChatColors.muted
                font = ChatFonts.body().deriveFont(Font.BOLD)
            })
            add(items)
        }
        add(box)
    }

    fun setEntries(entries: List<Pair<String, String>>) {
        items.removeAll()
        for ((content, status) in entries) {
            val mark = when (status) { "completed" -> "✔"; "in_progress" -> "◐"; else -> "○" }
            val row = WrapText("$mark  $content").apply {
                if (status == "completed") foreground = ChatColors.muted
            }
            items.add(row)
        }
        items.revalidate()
        items.repaint()
    }
}
