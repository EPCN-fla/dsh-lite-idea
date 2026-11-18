package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData

/**
 * Project-local session catalog cache. A snapshot may be stale for optimistic
 * rendering; fresh metadata additionally proves that bridge RPCs can be skipped.
 */
class SessionCatalogCache(
    private val metadataTtlMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var groups: SessionGroups? = null
    private val titles = HashMap<String, String>()
    private var metadataOwner: Any? = null
    private var metadataWorkspace: String? = null
    private var metadataAt: Long = 0L

    @Synchronized
    fun snapshot(): SessionGroups? = groups?.let(::withCachedTitles)

    @Synchronized
    fun knownTitles(): Map<String, String> = HashMap(titles)

    /** Update ACP-owned membership while retaining cached titles/subagents. */
    @Synchronized
    fun updateRoots(roots: List<SessionInfoData>): SessionGroups {
        rememberTitles(roots)
        val updated = SessionGroups(
            withCachedTitles(roots),
            withCachedTitles(groups?.subagents.orEmpty()),
        )
        groups = updated
        return updated
    }

    /** Only the same bridge instance/workspace and a fresh timestamp may skip RPCs. */
    @Synchronized
    fun freshMetadata(owner: Any, workspace: String): SessionGroups? {
        val current = groups ?: return null
        if (metadataOwner !== owner || metadataWorkspace != workspace) return null
        if (clock() - metadataAt > metadataTtlMs) return null
        return withCachedTitles(current)
    }

    @Synchronized
    fun putMetadata(owner: Any, workspace: String, value: SessionGroups): SessionGroups {
        rememberTitles(value.sessions + value.subagents)
        val titled = withCachedTitles(value)
        groups = titled
        metadataOwner = owner
        metadataWorkspace = workspace
        metadataAt = clock()
        return titled
    }

    /** Cache later title hydration without extending the metadata freshness window. */
    @Synchronized
    fun putHydrated(value: SessionGroups): SessionGroups {
        rememberTitles(value.sessions + value.subagents)
        val titled = withCachedTitles(value)
        groups = titled
        return titled
    }

    @Synchronized
    fun putTitle(sessionId: String, title: String) {
        val normalized = title.trim().takeIf { it.isNotEmpty() } ?: return
        titles[sessionId] = normalized
        groups = groups?.let { current ->
            SessionGroups(
                current.sessions.map { if (it.sessionId == sessionId) it.copy(title = normalized) else it },
                current.subagents.map { if (it.sessionId == sessionId) it.copy(title = normalized) else it },
            )
        }
    }

    @Synchronized
    fun remove(sessionId: String) {
        titles.remove(sessionId)
        groups = groups?.let { current ->
            SessionGroups(
                current.sessions.filterNot { it.sessionId == sessionId },
                current.subagents.filterNot { it.sessionId == sessionId },
            )
        }
    }

    private fun withCachedTitles(value: SessionGroups): SessionGroups = SessionGroups(
        withCachedTitles(value.sessions),
        withCachedTitles(value.subagents),
    )

    private fun withCachedTitles(rows: List<SessionInfoData>): List<SessionInfoData> = rows.map { row ->
        val cached = titles[row.sessionId]
        if (row.title.isNullOrBlank() && cached != null) row.copy(title = cached) else row
    }

    private fun rememberTitles(rows: List<SessionInfoData>) {
        rows.forEach { row ->
            row.title?.trim()?.takeIf { it.isNotEmpty() }?.let { titles[row.sessionId] = it }
        }
    }
}

/** Bridge metadata enriches exactly the fresh ACP roots; it never injects a root. */
fun mergeSessionMetadata(roots: List<SessionInfoData>, metadata: SessionGroups): SessionGroups {
    val titles = (metadata.sessions + metadata.subagents)
        .filter { !it.title.isNullOrBlank() }
        .associate { it.sessionId to it.title }
    return SessionGroups(
        sessions = roots.map { row -> row.copy(title = row.title ?: titles[row.sessionId]) },
        subagents = metadata.subagents,
    )
}
