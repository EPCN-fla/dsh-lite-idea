package com.dshlite.idea.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AsyncLineWriterTest {
    @Test fun `a blocked flush applies backpressure only to the writer thread`() {
        val flushEntered = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val flushThread = AtomicReference<String>()
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {}
            override fun flush() {
                flushThread.set(Thread.currentThread().name)
                flushEntered.countDown()
                releaseFlush.await(5, TimeUnit.SECONDS)
            }
            override fun close() {}
        }
        val writer = AsyncLineWriter(sink, "dsh-test-writer")
        val caller = Thread({ writer.writeLine("frame") }, "edt-simulation")
        try {
            caller.start()
            caller.join(1_000)
            assertFalse("the caller must not wait for pipe/socket flush", caller.isAlive)
            assertTrue(flushEntered.await(1, TimeUnit.SECONDS))
            assertNotEquals("edt-simulation", flushThread.get())
        } finally {
            releaseFlush.countDown()
            caller.join(1_000)
            writer.close()
        }
    }
}
