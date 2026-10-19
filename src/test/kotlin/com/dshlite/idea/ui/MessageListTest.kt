package com.dshlite.idea.ui

import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import java.awt.BorderLayout

class MessageListTest {
    @Test fun `resize and streaming recompute wrapped row heights without overlaps`() {
        SwingUtilities.invokeAndWait {
            val list = MessageList()
            val text = WrapText("Long content for testing wrapping. ".repeat(150))
            val row = MessageList.Row().apply { add(text) }
            list.add(row)
            list.add(MessageList.Row().apply { add(WrapText("last row")) })
            val scroll = JScrollPane(list).apply { horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER }
            val frame = JPanel(BorderLayout()).apply { add(scroll) }
            frame.setSize(320, 400)
            frame.addNotify()
            try {
                frame.validate()
                val narrow = list.preferredSize.height
                assertTrue(narrow > scroll.viewport.height)
                frame.setSize(760, 400)
                frame.validate()
                val wide = list.preferredSize.height
                assertTrue("wider window should reduce height: $narrow -> $wide", wide < narrow)
                assertEquals(list.preferredSize.height, list.height)
                text.append(" more text".repeat(500))
                frame.validate()
                assertTrue(list.preferredSize.height > wide)
                frame.setSize(320, 400)
                frame.validate()
                val children = list.components
                assertTrue(children[0].y + children[0].height < children[1].y)
                assertEquals(scroll.viewport.width, list.width)
            } finally { frame.removeNotify() }
        }
    }
}
