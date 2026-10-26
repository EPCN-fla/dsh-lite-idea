package com.dshlite.idea.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileMentionsTest {
    @Test fun `matches complete mention tokens and prefers the longest file name`() {
        val names = listOf("foo", "foo-2", "foo!bar", "with space.txt")
        assertEquals(setOf("foo-2"), referencedMentionNames("inspect @foo-2 please", names))
        assertEquals(setOf("foo!bar"), referencedMentionNames("inspect @foo!bar please", names))
        assertEquals(setOf("foo", "with space.txt"), referencedMentionNames("@foo and ＠with space.txt ", names))
        assertEquals(emptySet<String>(), referencedMentionNames("@unknown @foo2", names))
    }
}
