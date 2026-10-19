package com.dshlite.idea.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSelectionTest {
    @Test fun `opens with current session rather than first row`() {
        assertEquals(1, sessionSelectionIndex(listOf("first", "current", "last"), null, "current"))
    }
    @Test fun `refresh preserves user selection and falls back after deletion`() {
        assertEquals(2, sessionSelectionIndex(listOf("first", "current", "picked"), "picked", "current"))
        assertEquals(1, sessionSelectionIndex(listOf("first", "current"), "deleted", "current"))
        assertEquals(0, sessionSelectionIndex(listOf("first"), "deleted", "missing"))
        assertEquals(-1, sessionSelectionIndex(emptyList(), null, "missing"))
    }
}
