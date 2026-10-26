package com.dshlite.idea.bridge

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class BridgeClientTest {
    private fun withPeer(check: (BridgeClient, Socket) -> Unit) {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture<Socket>()
            val handshake = Thread {
                try {
                    val socket = server.accept()
                    val request = JsonParser.parseString(socket.getInputStream().bufferedReader().readLine()).asJsonObject
                    val response = """{"id":${request.get("id")},"result":{"capabilities":{"configSync":true}}}""" + "\n"
                    socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    peer.complete(socket)
                } catch (e: Exception) { peer.completeExceptionally(e) }
            }.apply { isDaemon = true; start() }
            val client = BridgeClient.connect(BridgeDiscoveryEntry(server.localPort, "test", 1, null, null, emptyList()), 3000)
            peer.get(3, TimeUnit.SECONDS).use { socket ->
                try { check(client, socket) } finally { client.close() }
            }
        }
    }

    private fun assertClosed(f: CompletableFuture<*>) {
        val e = assertThrows(ExecutionException::class.java) { f.get(2, TimeUnit.SECONDS) }
        assertTrue(e.cause is BridgeException)
    }

    @Test fun `handshake parses config sync capability`() = withPeer { client, _ ->
        assertTrue(client.capabilities?.configSync == true)
    }

    @Test fun `deadline rejects silent RPC without blocking subsequent requests`() = withPeer { client, socket ->
        val reader = socket.getInputStream().bufferedReader()
        val silent = client.request("silent", null, 20)
        JsonParser.parseString(reader.readLine()).asJsonObject
        val timeout = assertThrows(ExecutionException::class.java) { silent.get(2, TimeUnit.SECONDS) }
        assertTrue(timeout.cause is BridgeException)
        assertEquals("bridge/timeout", (timeout.cause as BridgeException).dataCode)

        val pong = client.request("ping", null, 1000)
        val request = JsonParser.parseString(reader.readLine()).asJsonObject
        socket.getOutputStream().write((("""{"id":${request.get("id")},"result":{"pong":true}}""" + "\n").toByteArray(Charsets.UTF_8)))
        assertTrue(pong.get(2, TimeUnit.SECONDS)?.get("pong")?.asBoolean == true)
    }

    @Test fun `local close fails every pending request immediately`() = withPeer { client, _ ->
        val pending = (1..8).map { client.request("never.answered") }
        client.close()
        pending.forEach(::assertClosed)
        assertClosed(client.request("after.close"))
    }

    @Test fun `peer EOF closes client and fires notification once`() = withPeer { client, socket ->
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val closed = CompletableFuture<Unit>()
        client.onClose = { calls.incrementAndGet(); closed.complete(Unit) }
        val pending = client.request("never.answered")
        socket.close()
        closed.get(2, TimeUnit.SECONDS)
        assertClosed(pending)
        client.close()
        assertEquals(1, calls.get())
        assertFalse(client.isOpen)
    }
}
