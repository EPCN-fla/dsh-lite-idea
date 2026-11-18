package com.dshlite.idea.chat

import org.junit.Assert.*
import org.junit.Test

class SessionReuseStateTest {
    @Test fun `blank session survives history switches and two IDE restarts`() {
        val state = SessionReuseState()
        state.created("blank")
        state.resumed("history")
        assertFalse(state.snapshot().lastSessionClean)

        val back = state.resumed("blank")
        assertEquals("blank", back.lastSessionId)
        assertTrue(back.lastSessionClean)

        val firstRestart = SessionReuseState(back.lastSessionId, back.lastSessionClean)
        assertEquals("blank", firstRestart.startupBlankSessionId())
        firstRestart.resumed("blank")
        firstRestart.resumed("history")
        val backAgain = firstRestart.resumed("blank")

        val secondRestart = SessionReuseState(backAgain.lastSessionId, backAgain.lastSessionClean)
        assertEquals("blank", secondRestart.startupBlankSessionId())
    }

    @Test fun `real content makes the current session ineligible for blank reuse`() {
        val state = SessionReuseState()
        state.created("blank")
        val dirty = state.content("blank")
        assertFalse(dirty.lastSessionClean)
        val restarted = SessionReuseState(dirty.lastSessionId, dirty.lastSessionClean)
        assertNull(restarted.startupBlankSessionId())
    }

    @Test fun `successful empty history proves a resumed session is blank`() {
        val state = SessionReuseState()
        state.resumed("empty-history")
        assertFalse(state.snapshot().lastSessionClean)
        val empty = state.transcript("empty-history", importedEmpty = true, hasDisplayedContent = false)
        assertTrue(empty.lastSessionClean)
        assertEquals("empty-history", SessionReuseState(empty.lastSessionId, empty.lastSessionClean).startupBlankSessionId())

        val nonempty = state.transcript("empty-history", importedEmpty = false, hasDisplayedContent = true)
        assertFalse(nonempty.lastSessionClean)
    }

    @Test fun `live content wins over an empty import and deletion clears reuse`() {
        val state = SessionReuseState()
        state.created("blank")
        val withLiveContent = state.transcript("blank", importedEmpty = true, hasDisplayedContent = true)
        assertFalse(withLiveContent.lastSessionClean)

        state.created("another")
        val deleted = state.deleted("another")
        assertFalse(deleted.lastSessionClean)
        assertNull(SessionReuseState(deleted.lastSessionId, deleted.lastSessionClean).startupBlankSessionId())
    }
}
