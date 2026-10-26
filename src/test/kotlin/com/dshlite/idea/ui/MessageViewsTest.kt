package com.dshlite.idea.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JEditorPane
import javax.swing.SwingUtilities

class MessageViewsTest {
    @Test fun `thought card refreshes content changed while collapsed`() {
        SwingUtilities.invokeAndWait {
            val card = ThoughtCard()
            card.setText("before-collapse")
            card.toggle()
            assertTrue(renderedThought(card).contains("before-collapse"))

            card.toggle()
            card.setText("after-collapse")
            card.toggle()

            val html = renderedThought(card)
            assertTrue(html.contains("after-collapse"))
            assertFalse(html.contains("before-collapse"))
        }
    }

    private fun renderedThought(card: ThoughtCard): String =
        card.body.components.filterIsInstance<JEditorPane>().single().text
}
