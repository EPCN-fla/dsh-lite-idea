package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData
import org.junit.Assert.*
import org.junit.Test

class SessionCatalogCacheTest {
    private fun row(id: String, title: String? = null) = SessionInfoData(id, "/workspace", title, null)

    @Test fun `snapshot reuses titles while ACP roots replace cached membership`() {
        val cache = SessionCatalogCache()
        val owner = Any()
        cache.putMetadata(owner, "/workspace", SessionGroups(
            sessions = listOf(row("old", "Old title")),
            subagents = listOf(SessionInfoData(
                "child", "/workspace", null, null, origin = "subagent", parentSession = "new",
            )),
        ))

        val updated = cache.updateRoots(listOf(row("new")))
        assertEquals(listOf("new"), updated.sessions.map { it.sessionId })
        assertEquals(listOf("child"), updated.subagents.map { it.sessionId })

        cache.putTitle("new", "Cached title")
        cache.putTitle("child", "Cached child")
        assertEquals("Cached title", cache.snapshot()?.sessions?.single()?.title)
        assertEquals("Cached child", cache.snapshot()?.subagents?.single()?.title)
        assertEquals(mapOf("old" to "Old title", "new" to "Cached title", "child" to "Cached child"), cache.knownTitles())
    }

    @Test fun `fresh metadata is scoped to owner workspace and TTL`() {
        var now = 100L
        val cache = SessionCatalogCache(metadataTtlMs = 60L, clock = { now })
        val owner = Any()
        val groups = SessionGroups(listOf(row("root", "Title")), emptyList())
        cache.putMetadata(owner, "/workspace", groups)

        assertEquals("Title", cache.freshMetadata(owner, "/workspace")?.sessions?.single()?.title)
        assertNull(cache.freshMetadata(Any(), "/workspace"))
        assertNull(cache.freshMetadata(owner, "/other"))
        now = 160L
        assertNotNull(cache.freshMetadata(owner, "/workspace"))
        now = 161L
        assertNull(cache.freshMetadata(owner, "/workspace"))
    }

    @Test fun `hydration fills titles without extending metadata freshness`() {
        var now = 0L
        val cache = SessionCatalogCache(metadataTtlMs = 60L, clock = { now })
        val owner = Any()
        cache.putMetadata(owner, "/workspace", SessionGroups(listOf(row("root")), emptyList()))
        now = 30L
        cache.putHydrated(SessionGroups(listOf(row("root", "Hydrated")), emptyList()))
        assertEquals("Hydrated", cache.snapshot()?.sessions?.single()?.title)
        now = 61L
        assertNull(cache.freshMetadata(owner, "/workspace"))
    }

    @Test fun `deletion removes cached rows and merge cannot inject cached roots`() {
        val cache = SessionCatalogCache()
        cache.putHydrated(SessionGroups(listOf(row("old", "Old")), emptyList()))
        val merged = mergeSessionMetadata(listOf(row("new")), cache.snapshot()!!)
        assertEquals(listOf("new"), merged.sessions.map { it.sessionId })
        cache.remove("old")
        assertTrue(cache.snapshot()?.sessions?.isEmpty() == true)
        assertFalse(cache.knownTitles().containsKey("old"))
    }
}
