package com.dshlite.idea.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Snapshot descendants before killing the root, which would orphan them. */
private fun processTree(process: Process): List<ProcessHandle> {
    val root = process.toHandle()
    val descendants = runCatching {
        root.descendants().use { stream -> stream.toList() }
    }.getOrDefault(emptyList())
    return (descendants.asReversed() + root).distinct()
}

/** Best-effort immediate tree termination for startup/reset paths. */
internal fun destroyProcessTree(process: Process, forcibly: Boolean = false) {
    processTree(process).forEach { handle ->
        runCatching {
            if (forcibly) handle.destroyForcibly() else handle.destroy()
        }
    }
}

/**
 * Give the whole tree a graceful period, then force remaining processes and
 * wait once more. Runs on the ACP close daemon, never on the EDT.
 */
internal fun terminateProcessTree(process: Process, graceMs: Long = 3_000) {
    val tree = processTree(process)
    val exited = tree.map { it.onExit() }.toTypedArray()
    tree.forEach { handle -> runCatching { handle.destroy() } }
    awaitTree(exited, graceMs)
    tree.forEach { handle ->
        if (handle.isAlive) runCatching { handle.destroyForcibly() }
    }
    awaitTree(exited, 1_000)
}

private fun awaitTree(exited: Array<CompletableFuture<ProcessHandle>>, timeoutMs: Long) {
    if (exited.isEmpty()) return
    try {
        CompletableFuture.allOf(*exited).get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (_: Exception) {
        // Timeout/unsupported handle: forced destruction is handled by the caller.
    }
}
