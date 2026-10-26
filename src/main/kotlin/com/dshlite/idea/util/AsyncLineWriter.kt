package com.dshlite.idea.util

import java.io.IOException
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Serializes newline-delimited writes on one daemon thread. Socket and process
 * pipes apply backpressure in flush(), so callers (including the EDT) must only
 * enqueue frames, never perform the blocking write themselves.
 */
internal class AsyncLineWriter(
    private val writer: Writer,
    threadName: String,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    fun writeLine(line: String, onFailure: (Exception) -> Unit = {}) {
        if (closed) {
            onFailure(IOException("connection writer is closed"))
            return
        }
        try {
            executor.execute {
                if (closed) {
                    onFailure(IOException("connection writer is closed"))
                    return@execute
                }
                try {
                    synchronized(writer) {
                        writer.write(line)
                        writer.write("\n")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
        } catch (e: RejectedExecutionException) {
            onFailure(e)
        }
    }

    /** Stop accepting frames and discard queued writes. Does not block the caller. */
    fun shutdown() {
        closed = true
        executor.shutdownNow()
    }

    /** Close the underlying stream after the peer/process has been terminated. */
    fun close() {
        shutdown()
        synchronized(writer) { writer.close() }
    }
}
