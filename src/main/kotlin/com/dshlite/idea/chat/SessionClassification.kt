package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData

/** Root/fork history and durable subagent history are separate catalogs. */
data class SessionGroups(
    val sessions: List<SessionInfoData>,
    val subagents: List<SessionInfoData>,
)

/** Split by the explicit origin marker, never by parentSession alone. */
fun partitionSessions(rows: Collection<SessionInfoData>): SessionGroups = SessionGroups(
    sessions = rows.filter { it.origin != "subagent" },
    subagents = rows.filter { it.origin == "subagent" },
)

/** Only direct children of the active main session are read-only viewable. */
fun directSubagents(rows: Collection<SessionInfoData>, parentSessionId: String?): List<SessionInfoData> {
    if (parentSessionId == null) return emptyList()
    return rows.filter { it.origin == "subagent" && it.parentSession == parentSessionId }
}

data class SubagentRequestIdentity(
    val parentSessionId: String,
    val sessionId: String,
    val requestId: Long,
)

fun matchesSubagentRequest(expected: SubagentRequestIdentity?, actual: SubagentRequestIdentity): Boolean =
    expected != null &&
        expected.parentSessionId == actual.parentSessionId &&
        expected.sessionId == actual.sessionId &&
        expected.requestId == actual.requestId
