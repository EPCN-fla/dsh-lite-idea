package com.dshlite.idea.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionTranscriptCacheTest {
    @Test fun `empty transcripts are cache hits and roots do not overwrite children`() {
        val cache = SessionTranscriptCache()
        var calls = 0
        val loader = {
            calls++
            emptyList<HistoryMessage>()
        }
        assertEquals(emptyList<HistoryMessage>(), cache.importShared("root", loader))
        assertEquals(emptyList<HistoryMessage>(), cache.importShared("root", loader))
        assertEquals(listOf(HistoryMessage.User("child")), cache.importShared("child") {
            listOf(HistoryMessage.User("child"))
        })
        assertEquals(1, calls)
        assertEquals(emptyList<HistoryMessage>(), cache.get("root"))
    }

    @Test fun `concurrent misses share one in-flight import`() {
        val cache = SessionTranscriptCache()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<List<HistoryMessage>?> {
                    cache.importShared("same") {
                        calls.incrementAndGet()
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        listOf(HistoryMessage.Assistant("loaded"))
                    }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            results.forEach { assertEquals(listOf(HistoryMessage.Assistant("loaded")), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
