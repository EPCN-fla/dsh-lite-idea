package com.dshlite.idea.util

import java.util.concurrent.CompletableFuture

/**
 * Run a blocking read on a dedicated daemon thread. Blocking stream drains must
 * NOT use CompletableFuture.supplyAsync: that lands on the shared ForkJoinPool
 * common pool, where a stuck pipe read can starve unrelated work.
 */
fun <T> ioAsync(name: String = "dsh-io-drain", read: () -> T): CompletableFuture<T> {
    val f = CompletableFuture<T>()
    Thread({
        try {
            f.complete(read())
        } catch (t: Throwable) {
            f.completeExceptionally(t)
        }
    }, name).apply { isDaemon = true; start() }
    return f
}
