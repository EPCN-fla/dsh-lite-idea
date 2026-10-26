package com.dshlite.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlNodeRendererFactory
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color
import javax.swing.JEditorPane

/**
 * Markdown → HTML for Swing's HTML engine (HTML 3.2 + a CSS1 subset), with
 * the workarounds that engine needs: no alpha colors, no pre-wrap, no
 * inline padding, no border-collapse.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()

    /** Never emit <img>: HTMLEditorKit would fetch remote URLs from untrusted
     *  model output (privacy + UI-thread hang risk). */
    private class ImageAsText(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes() = setOf(Image::class.java)
        override fun render(node: Node) {
            context.writer.text("[🖼 ")
            var child = node.firstChild
            while (child != null) { context.render(child); child = child.next }
            context.writer.text("]")
        }
    }

    /**
     * Code blocks as a styled single-cell table with explicit line breaks:
     * Swing has no `white-space: pre-wrap`, so <pre> lines never wrap and are
     * clipped at the window edge. (A table cell, unlike a div, paints its
     * border reliably.) Indentation survives as &nbsp;.
     */
    private class CodeBlockAsTable(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes() = setOf(FencedCodeBlock::class.java, IndentedCodeBlock::class.java)
        override fun render(node: Node) {
            val literal = when (node) {
                is FencedCodeBlock -> node.literal
                is IndentedCodeBlock -> node.literal
                else -> ""
            }.removeSuffix("\n")
            context.writer.line()
            context.writer.raw(
                // Swing ignores CSS padding on cells; cellpadding works.
                "<table class=\"code\" width=\"100%\" cellspacing=\"0\" cellpadding=\"" + JBUI.scale(8) +
                    "\"><tr><td class=\"code\">" +
                    codeLinesHtml(literal) + "</td></tr></table>"
            )
            context.writer.line()
        }
    }

    /** Inline code with a hair of padding inside its background (Swing
     *  ignores padding on inline elements, so pad with tiny spaces). */
    private class InlineCode(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes() = setOf(Code::class.java)
        override fun render(node: Node) {
            val pad = "<span class=\"pad\">&nbsp;</span>"
            context.writer.raw("<code>$pad")
            context.writer.text((node as Code).literal)
            context.writer.raw("$pad</code>")
        }
    }

    /** Swing paints <hr> as a dark etched rule; draw a hairline instead. */
    private class ThematicBreakAsRule(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes() = setOf(ThematicBreak::class.java)
        override fun render(node: Node) {
            context.writer.line()
            context.writer.raw("<div class=\"hr\">&nbsp;</div>")
            context.writer.line()
        }
    }

    /** Swing knows HTML 3.2 <s>, but ignores HTML5 <del>. */
    private class StrikeAsS(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes() = setOf(Strikethrough::class.java)
        override fun render(node: Node) {
            context.writer.raw("<s>")
            var child = node.firstChild
            while (child != null) { context.render(child); child = child.next }
            context.writer.raw("</s>")
        }
    }

    /** Tables without spacing; cells carry the borders (no border-collapse). */
    private val tableAttributes = AttributeProvider { node, tagName, attributes ->
        if (node is TableBlock && tagName == "table") {
            attributes["cellspacing"] = "0"
            attributes["cellpadding"] = JBUI.scale(5).toString()
            attributes["class"] = "md"
        }
    }

    internal fun codeLinesHtml(code: String): String =
        code.split('\n').joinToString("<br>") { line ->
            if (line.isEmpty()) return@joinToString "&nbsp;"
            val sb = StringBuilder()
            var leading = true
            var prevSpace = false
            var column = 0
            fun appendSpace() {
                // Leading and repeated spaces must survive whitespace collapsing.
                sb.append(if (leading || prevSpace) "&nbsp;" else " ")
                prevSpace = true
                column++
            }
            for (ch in line) {
                when (ch) {
                    ' ' -> appendSpace()
                    '\t' -> repeat(4 - column % 4) { appendSpace() }
                    else -> {
                        leading = false
                        prevSpace = false
                        column++
                        when (ch) {
                            '&' -> sb.append("&amp;")
                            '<' -> sb.append("&lt;")
                            '>' -> sb.append("&gt;")
                            '"' -> sb.append("&quot;")
                            else -> sb.append(ch)
                        }
                    }
                }
            }
            sb.toString()
        }

    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .escapeHtml(true)
        // Model output is untrusted. The default sanitizer also allows data:,
        // so use an explicit protocol allowlist instead of relying on defaults.
        .sanitizeUrls(true)
        .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
        // Chat semantics (VS Code uses marked `breaks: true`): a newline is a newline.
        .softbreak("<br>")
        .attributeProviderFactory { tableAttributes }
        .nodeRendererFactory(HtmlNodeRendererFactory { ImageAsText(it) })
        .nodeRendererFactory(HtmlNodeRendererFactory { CodeBlockAsTable(it) })
        .nodeRendererFactory(HtmlNodeRendererFactory { InlineCode(it) })
        .nodeRendererFactory(HtmlNodeRendererFactory { ThematicBreakAsRule(it) })
        .nodeRendererFactory(HtmlNodeRendererFactory { StrikeAsS(it) })
        // First registered renderer wins; put Swing adapters before GFM.
        .extensions(extensions)
        .build()

    private val FIRST_BLOCK = Regex("^\\s*<(p|h[1-6]|ul|ol|blockquote|table|div)([ >/])")

    fun toHtml(markdown: String): String =
        // The first block sits flush with the top of the message.
        renderer.render(parser.parse(markdown))
            .replaceFirst(FIRST_BLOCK, "<$1 style=\"margin-top:0\"$2")

    /** Swing's CSS has no alpha channel: rgba() / 8-digit colors silently turn
     *  black. Every color here is an opaque blend against the panel background. */
    private fun rgb(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun css(muted: Boolean): List<String> {
        val base = if (muted) ChatFonts.small() else ChatFonts.body()
        val size = base.size
        val mono = ChatFonts.mono(base)
        val fg = if (muted) ChatColors.muted else ChatColors.foreground
        val faint = rgb(ChatColors.muted)
        val codeBg = rgb(ChatColors.codeBackground)
        val codeBorder = rgb(ChatColors.mix(ChatColors.background, ChatColors.foreground, 0.14))
        val rule = rgb(ChatColors.mix(ChatColors.background, ChatColors.foreground, 0.16))
        val headBg = rgb(ChatColors.mix(ChatColors.background, ChatColors.foreground, 0.05))
        val inlineFg = rgb(JBColor(Color(0xB3305C), Color(0xE5A0B8)))
        val link = rgb(JBColor.namedColor("Link.activeForeground", JBColor.BLUE))
        val family = "'${base.family}', 'Microsoft YaHei UI', 'Microsoft YaHei', 'PingFang SC', 'Noto Sans CJK SC', sans-serif"
        val gap = JBUI.scale(8)
        return listOf(
            "body { font-family: $family; font-size: ${size}pt; color: ${rgb(fg)}; margin: 0; padding: 0; }",
            "p { margin-top: ${gap}px; margin-bottom: 0; }",
            "h1 { font-size: ${size + 6}pt; margin-top: ${gap + 6}px; margin-bottom: 4px; padding-bottom: 3px; border-bottom: 1px solid $rule; }",
            "h2 { font-size: ${size + 3}pt; margin-top: ${gap + 4}px; margin-bottom: 4px; padding-bottom: 2px; border-bottom: 1px solid $rule; }",
            "h3 { font-size: ${size + 1}pt; margin-top: ${gap + 2}px; margin-bottom: 2px; }",
            "h4, h5, h6 { font-size: ${size}pt; margin-top: ${gap}px; margin-bottom: 2px; }",
            "ul, ol { margin-top: ${gap / 2}px; margin-bottom: 0; margin-left: ${JBUI.scale(20)}px; }",
            "li { margin-top: 3px; }",
            "li p { margin-top: 2px; }",
            "code { font-family: '${mono.family}'; font-size: ${mono.size}pt; background-color: $codeBg; color: $inlineFg; }",
            "span.pad { font-size: 3pt; }",
            "table.code { margin-top: ${gap}px; margin-bottom: 0; border: 1px solid $codeBorder; background-color: $codeBg; }",
            "td.code { font-family: '${mono.family}'; font-size: ${mono.size}pt; color: ${rgb(fg)}; }",
            "table.md { margin-top: ${gap}px; border-top: 1px solid $rule; border-left: 1px solid $rule; }",
            "table.md th { background-color: $headBg; font-weight: bold; text-align: left; border-right: 1px solid $rule; border-bottom: 1px solid $rule; }",
            "table.md td { border-right: 1px solid $rule; border-bottom: 1px solid $rule; }",
            "blockquote { margin: ${gap}px 0 0 0; padding: 2px 0 2px 10px; border-left: 3px solid $rule; color: $faint; }",
            "blockquote p { margin-top: 2px; }",
            "s { color: $faint; }",
            "a { color: $link; text-decoration: none; }",
            "div.hr { font-size: 2pt; margin-top: ${gap + 4}px; margin-bottom: ${gap}px; border-top: 1px solid $rule; }",
        )
    }

    /** Read-only editor pane that renders markdown as HTML with IDE fonts. */
    class View(private val muted: Boolean = false) : JEditorPane() {
        private var lastHtml: String? = null

        init {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            // Word-wrap view factory: long paths/URLs break instead of
            // forcing the pane wider than the window.
            val kit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            css(muted).forEach { kit.styleSheet.addRule(it) }
            editorKit = kit
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    e.url?.let { com.intellij.ide.BrowserUtil.browse(it) }
                }
            }
        }

        fun setMarkdown(markdown: String) {
            val html = "<html><body>${toHtml(markdown)}</body></html>"
            if (html == lastHtml) return
            lastHtml = html
            text = html
            caretPosition = 0
        }
    }
}
