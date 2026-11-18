package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData
import org.junit.Assert.*
import org.junit.Test

class SessionClassificationTest {
    private fun row(
        id: String,
        origin: String? = null,
        parent: String? = null,
        live: Boolean = false,
    ) = SessionInfoData(id, "/workspace", null, null, live = live, origin = origin, parentSession = parent)

    @Test fun `origin separates children while ordinary forks stay in root history`() {
        val groups = partitionSessions(listOf(
            row("root"), row("fork", parent = "root"),
            row("child", origin = "subagent", parent = "root", live = true),
            row("stored-child", origin = "subagent", parent = "root"),
            row("nested", origin = "subagent", parent = "child"),
            row("foreign", origin = "subagent", parent = "other"),
        ))
        assertEquals(listOf("root", "fork"), groups.sessions.map { it.sessionId })
        assertEquals(listOf("child", "stored-child"), directSubagents(groups.subagents, "root").map { it.sessionId })
        assertTrue(directSubagents(groups.subagents, null).isEmpty())
    }

    @Test fun `subagent response identity includes parent child and monotonically increasing request`() {
        val expected = SubagentRequestIdentity("parent", "child", 4)
        assertTrue(matchesSubagentRequest(expected, expected))
        assertFalse(matchesSubagentRequest(expected, expected.copy(parentSessionId = "other")))
        assertFalse(matchesSubagentRequest(expected, expected.copy(sessionId = "other")))
        assertFalse(matchesSubagentRequest(expected, expected.copy(requestId = 3)))
        assertFalse(matchesSubagentRequest(null, expected))
    }
}
