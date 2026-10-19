package com.dshlite.idea.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownRendererTest {
    @Test fun `renders tables strike and soft breaks with Swing compatible HTML`() {
        val html = MarkdownRenderer.toHtml("first\nsecond\n\n~~deleted~~\n\n| A | B |\n|---|---|\n| c | d |")
        assertTrue(html.contains("first<br>second"))
        assertTrue(html.contains("<s>deleted</s>"))
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("cellpadding=\"5\""))
        assertTrue(html.contains("<th>A</th>"))
    }

    @Test fun `escapes raw markup and never embeds remote images or unsafe links`() {
        val html = MarkdownRenderer.toHtml("<img src=\"https://example.com/a\">\n\n![alt](https://example.com/p.png)\n\n[x](javascript:alert%281%29)")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img"))
        assertTrue(html.contains("[🖼 alt]"))
        assertFalse(html.contains("href=\"javascript:"))
    }

    @Test fun `code blocks preserve indentation and escape markup`() {
        val html = MarkdownRenderer.toHtml("```html\n  <script>alert(1)</script>\n\n    next\n``` ")
        assertTrue(html.contains("class=\"code\""))
        assertFalse(html.contains("<pre"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&nbsp;&nbsp;&lt;script&gt;"))
        assertTrue(html.contains("<br>&nbsp;<br>"))
    }
}
