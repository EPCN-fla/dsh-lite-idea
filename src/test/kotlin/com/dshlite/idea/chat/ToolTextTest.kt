package com.dshlite.idea.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTextTest {
    @Test fun `strict key value parser accepts multiline rows and embedded markup`() {
        assertEquals(
            listOf("exit" to "0", "message" to "first\n<em>second</em>"),
            ToolText.xmlRows(" <exit>0</exit>\n<message>first\n<em>second</em></message> "),
        )
    }

    @Test fun `strict key value parser rejects malformed or oversized input without regex backtracking`() {
        for (input in listOf(
            "<a>unterminated",
            "<a>x</b>",
            "prefix<a>x</a>",
            "<1bad>x</1bad>",
            "<a>x</a>trailing",
            "<a>" + "x".repeat(100_001) + "</a>",
        )) {
            assertNull(input.take(80), ToolText.xmlRows(input))
        }
        assertNull(ToolText.xmlRows("<a>".repeat(30_000)))
    }
}
