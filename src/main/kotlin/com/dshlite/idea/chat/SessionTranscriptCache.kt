package com.dshlite.idea.chat

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * Small process-local transcript cache shared by roots and subagents.
 * Empty transcripts are real cache hits and concurrent misses share one export.
 */
class SessionTranscriptCache {
    private val values = ConcurrentHashMap<String, List<HistoryMessage>>()
    private val imports = ConcurrentHashMap<String, FutureTask<List<HistoryMessage>?>>()

    fun get(sessionId: String): List<HistoryMessage>? = values[sessionId]

    fun put(sessionId: String, messages: List<HistoryMessage>) {
        values[sessionId] = messages
    }

    fun remove(sessionId: String) {
        values.remove(sessionId)
    }

    /** A failed import is retryable; a successful empty import stays cached. */
    fun importShared(sessionId: String, supplier: () -> List<HistoryMessage>?): List<HistoryMessage>? {
        get(sessionId)?.let { return it }
        val task = FutureTask(Callable(supplier))
        val active = imports.putIfAbsent(sessionId, task) ?: task
        if (active === task) task.run()
        try {
            val result = active.get()
            if (result != null) values[sessionId] = result
            return result
        } finally {
            imports.remove(sessionId, active)
        }
    }
}
