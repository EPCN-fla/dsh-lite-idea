package com.dshlite.idea.acp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AcpClientTest {
    /** In-memory agent: answers initialize, keeps every later request pending. */
    private class AgentProcess : Process() {
        private val input = PipedInputStream()
        private val agentOutput = PipedOutputStream(input)
        private val dead = CountDownLatch(1)
        private val requests = ByteArrayOutputStream()
        val frames = java.util.concurrent.LinkedBlockingQueue<Pair<JsonObject, String>>()
        @Volatile var alive = true
        private val stdin = object : OutputStream() {
            override fun write(b: Int) { requests.write(b) }
            override fun flush() {
                val lines = requests.toString(Charsets.UTF_8).trim()
                requests.reset()
                if (lines.isBlank()) return
                for (line in lines.lineSequence()) {
                    val request = JsonParser.parseString(line).asJsonObject
                    frames.add(request to Thread.currentThread().name)
                    if (request.get("method")?.asString == "initialize") {
                        send("""{"id":${request.get("id")},"result":{"agentInfo":{"name":"test"}}}""")
                    }
                }
            }
        }
        fun send(line: String) { agentOutput.write((line + "\n").toByteArray(Charsets.UTF_8)); agentOutput.flush() }
        fun finish() { alive = false; dead.countDown(); agentOutput.close() }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { dead.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = dead.await(timeout, unit)
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
        override fun isAlive(): Boolean = alive
        override fun destroy() { finish() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun `process exit drains final stdout chunks before notifying exit`() {
        val process = AgentProcess()
        val chunks = ArrayList<String>()
        val exit = CompletableFuture<Unit>()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) { chunks.add(update.text!!) }
            override fun onPermission(request: PermissionRequestData) = CompletableFuture.completedFuture<String?>(null)
            override fun onExit(code: Int, stderrTail: String) { exit.complete(Unit) }
        }, 2000)
        repeat(100) { i -> process.send("""{"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$i"}}}}""") }
        process.finish()
        exit.get(4, TimeUnit.SECONDS)
        assertEquals((0..99).map { it.toString() }, chunks)
        assertFalse(client.isAlive())
        assertThrows(ExecutionException::class.java) { client.request("after.exit", JsonObject()).get(1, TimeUnit.SECONDS) }
    }

    @Test fun `requests enqueue ndjson writes instead of flushing on the caller`() {
        val process = AgentProcess()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {}
            override fun onPermission(request: PermissionRequestData) = CompletableFuture.completedFuture<String?>(null)
        }, 2000)
        process.frames.clear() // discard the initialize request
        try {
            val caller = Thread.currentThread().name
            client.request("test.request", JsonObject())
            val (frame, writerThread) = process.frames.poll(2, TimeUnit.SECONDS)
                ?: throw AssertionError("request frame was not written")
            assertEquals("test.request", frame.get("method").asString)
            assertNotEquals(caller, writerThread)
        } finally {
            client.close()
        }
    }

    @Test fun `control requests time out without killing the connection`() {
        val process = AgentProcess()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {}
            override fun onPermission(request: PermissionRequestData) = CompletableFuture.completedFuture<String?>(null)
        }, 2000)
        try {
            val error = assertThrows(ExecutionException::class.java) {
                client.request("silent.control", JsonObject(), 20).get(1, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is AcpException)
            assertTrue(error.cause!!.message!!.contains("timed out"))
            assertTrue(client.isAlive())
        } finally {
            client.close()
        }
    }

    @Test fun `malformed permission params receive invalid params instead of hanging`() {
        val process = AgentProcess()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {}
            override fun onPermission(request: PermissionRequestData): CompletableFuture<String?> =
                throw AssertionError("malformed request must not reach the responder")
        }, 2000)
        try {
            process.send("""{"id":"bad","method":"session/request_permission","params":{"sessionId":"s","options":"bad"}}""")
            val response = responseFor(process, "bad")
            assertEquals(-32602, response.getAsJsonObject("error").get("code").asInt)
        } finally {
            client.close()
        }
    }

    @Test fun `throwing permission responder settles the request as cancelled`() {
        val process = AgentProcess()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {}
            override fun onPermission(request: PermissionRequestData): CompletableFuture<String?> =
                throw IllegalStateException("dialog failed")
        }, 2000)
        try {
            process.send("""{"id":"perm","method":"session/request_permission","params":{"sessionId":"s","options":[]}}""")
            val response = responseFor(process, "perm")
            assertEquals("cancelled", response.getAsJsonObject("result").getAsJsonObject("outcome").get("outcome").asString)
        } finally {
            client.close()
        }
    }

    private fun responseFor(process: AgentProcess, id: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val (frame, _) = process.frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (frame.get("id")?.asString == id) return frame
        }
        throw AssertionError("no response for $id")
    }

    @Test fun `close completes unanswered requests`() {
        val process = AgentProcess()
        val client = AcpClient.start(process, "test", object : AcpClientListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {}
            override fun onPermission(request: PermissionRequestData) = CompletableFuture.completedFuture<String?>(null)
        }, 2000)
        val pending = client.request("never.answers", JsonObject())
        client.close()
        val error = assertThrows(ExecutionException::class.java) { pending.get(1, TimeUnit.SECONDS) }
        assertTrue(error.cause is AcpException)
        assertFalse(client.isAlive())
    }
}
