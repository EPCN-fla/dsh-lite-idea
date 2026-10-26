package com.dshlite.idea.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JEditorPane
import javax.swing.JLabel
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

    @Test fun `tool cards never enable Swing HTML for untrusted labels or input keys`() {
        SwingUtilities.invokeAndWait {
            val payload = "<html><img src='http://127.0.0.1:9/unreachable'>"
            val input = com.google.gson.JsonObject().apply { addProperty(payload, "value") }
            val card = ToolCard(
                ToolCard.ToolState(
                    title = payload,
                    subtitle = payload,
                    kind = payload,
                    status = payload,
                    input = input,
                )
            )
            card.toggle() // build the input-key labels too

            val labels = descendants(card).filterIsInstance<JLabel>()
            val affected = labels.filter { it.text == payload }
            assertTrue("expected header and input-key labels", affected.size >= 5)
            for (label in affected) {
                assertTrue(label.getClientProperty("html.disable") == true)
                assertNull("Swing HTML renderer must not be created", label.getClientProperty("html"))
            }
            val tooltip = affected.first { it.toolTipText != null }.toolTipText
            assertFalse(tooltip.startsWith("<html>"))
            assertTrue(tooltip.contains(payload))
        }
    }

    private fun descendants(component: Component): List<Component> = buildList {
        add(component)
        if (component is Container) component.components.forEach { addAll(descendants(it)) }
    }

    private fun renderedThought(card: ThoughtCard): String =
        card.body.components.filterIsInstance<JEditorPane>().single().text
}
