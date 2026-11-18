package com.dshlite.idea.bridge

import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BridgeDiscoveryTest {
    private fun entry(pid: Int, startedAt: Long?, directories: List<String> = listOf("/ws/a")) =
        BridgeDiscoveryEntry(7300 + pid, "token-$pid", pid, startedAt, null, directories)

    @Test fun `startedAt accepts numeric legacy values and current ISO timestamps`() {
        assertEquals(1234L, parseBridgeStartedAt(JsonPrimitive(1234)))
        val iso = "2026-11-17T05:00:02.000Z"
        assertEquals(Instant.parse(iso).toEpochMilli(), parseBridgeStartedAt(JsonPrimitive(iso)))
        assertNull(parseBridgeStartedAt(JsonPrimitive("not-a-date")))
        assertNull(parseBridgeStartedAt(null))
    }

    @Test fun `rank returns every workspace match newest first`() {
        val entries = listOf(
            entry(1, Instant.parse("2026-11-17T05:00:00.000Z").toEpochMilli()),
            entry(2, Instant.parse("2026-11-17T05:00:02.000Z").toEpochMilli()),
            entry(3, Instant.parse("2026-11-17T05:00:01.000Z").toEpochMilli()),
            entry(4, null),
            entry(5, 9999, listOf("/ws/other")),
        )
        assertEquals(listOf(2, 3, 1, 4), rankBridgeEntries(entries, "/ws/a/", false).map { it.pid })
        assertEquals(emptyList<Int>(), rankBridgeEntries(entries, "/ws", false).map { it.pid })
    }

    @Test fun `rank optionally uses Windows case insensitive path matching`() {
        val entries = listOf(entry(1, 1, listOf("C:\\project\\")))
        assertEquals(listOf(1), rankBridgeEntries(entries, "c:\\PROJECT", true).map { it.pid })
        assertEquals(emptyList<Int>(), rankBridgeEntries(entries, "c:\\PROJECT", false).map { it.pid })
    }

    @Test fun `stale newest candidate falls through to the next working handshake`() {
        val candidates = rankBridgeEntries(listOf(entry(1, 100), entry(2, 200)), "/ws/a", false)
        val attempts = mutableListOf<Int>()
        val connected = firstSuccessfulCandidate(candidates) { candidate ->
            attempts += candidate.pid
            candidate.pid != 2 // newest discovery file is stale
        }
        assertEquals(listOf(2, 1), attempts)
        assertEquals(1, connected?.pid)
    }

    @Test fun `candidate attempts stop immediately after a successful handshake`() {
        val attempts = mutableListOf<Int>()
        val connected = firstSuccessfulCandidate(listOf(2, 1, 3)) { candidate ->
            attempts += candidate
            candidate == 1
        }
        assertEquals(1, connected)
        assertEquals(listOf(2, 1), attempts)
    }
}
