package com.dshlite.idea.ui

import com.dshlite.idea.chat.ChangedFile
import com.dshlite.idea.chat.ChangedFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.SwingUtilities

class ChangesBarTest {
    @Test fun `summary lists created and modified files and rows open them`() {
        SwingUtilities.invokeAndWait {
            var opened: ChangedFile? = null
            val bar = ChangesBar { opened = it }
            assertFalse(bar.isVisible)
            val created = ChangedFile("/w/new.ts", "new.ts", ChangedFileKind.CREATED)
            val modified = ChangedFile("/w/a.ts", "src/a.ts", ChangedFileKind.MODIFIED)
            bar.setFiles(listOf(modified, created))
            assertTrue(bar.isVisible)

            val buttons = ArrayList<AbstractButton>()
            collectButtons(bar, buttons)
            assertTrue(buttons.first().text.contains("2"))
            assertTrue(buttons.first().text.contains("1"))
            val row = buttons.single { it.toolTipText == modified.path }
            row.doClick()
            assertSame(modified, opened)

            buttons.first().doClick()
            assertTrue(buttons.first().text.startsWith("▾"))
            bar.setFiles(emptyList())
            assertFalse(bar.isVisible)
        }
    }

    private fun collectButtons(component: java.awt.Component, out: MutableList<AbstractButton>) {
        if (component is AbstractButton) out.add(component)
        if (component is Container) for (child in component.components) collectButtons(child, out)
    }
}
