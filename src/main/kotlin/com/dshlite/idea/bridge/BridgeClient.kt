/**
 * Client for the dsh-vscode-bridge dsh plugin: ndjson JSON-RPC 2.0 over
 * loopback TCP, token on every request, server push as `bridge.event`
 * notifications. Ported from dsh-lite-vscode src/bridge/client.ts.
 */
package com.dshlite.idea.bridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BridgeException(message: String, val rpcCode: Int, val dataCode: String?) : Exception(message)

data class BridgeCapabilities(
    val workspaceGrouping: Boolean = false,
    val sessionTitle: Boolean = false,
    val sessionArchive: Boolean = false,
    val presets: Boolean = false,
    val permissions: Boolean = false,
    val eventPush: Boolean = false,
    val commands: Boolean = false,
    val skills: Boolean = false,
    val sessionExport: Boolean = false,
    /** bridge 0.4.0+: explicit Web model/preset import. Absent on older bridges. */
    val configSync: Boolean = false,
)

data class BridgeHandshake(
    val plugin: String?,
    val version: String?,
    val dshVersion: String?,
    val capabilities: BridgeCapabilities,
)

/** One published bridge instance: `$DSH_HOME/vscode-bridge/<pid>.json`. */
data class BridgeDiscoveryEntry(
    val port: Int,
    val token: String,
    val pid: Int,
    val startedAt: Long?,
    val dshVersion: String?,
    val directories: List<String>,
)

class BridgeClient private constructor(
    private val socket: Socket,
    private val token: String,
) : ConfigSyncRpc {
    companion object {
        private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

        fun connect(
            entry: BridgeDiscoveryEntry,
            timeoutMs: Long = 10_000,
            onEvent: (JsonObject) -> Unit = {},
            onClose: () -> Unit = {},
        ): BridgeClient {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), entry.port), timeoutMs.toInt())
            socket.tcpNoDelay = true
            val client = BridgeClient(socket, entry.token)
            // Handlers attach before the reader starts: no event/EOF can fall
            // through the gap between connect() and handler assignment.
            client.onEvent = onEvent
            client.onClose = onClose
            client.startReader()
            try {
                val hs = client.request("bridge.handshake", JsonObject()).get(timeoutMs, TimeUnit.MILLISECONDS)
                val caps = hs?.get("capabilities") as? JsonObject
                client.handshake = BridgeHandshake(
                    plugin = hs?.str("plugin"),
                    version = hs?.str("version"),
                    dshVersion = hs?.str("dshVersion"),
                    capabilities = BridgeCapabilities(
                        workspaceGrouping = caps.bool("workspaceGrouping"),
                        sessionTitle = caps.bool("sessionTitle"),
                        sessionArchive = caps.bool("sessionArchive"),
                        presets = caps.bool("presets"),
                        permissions = caps.bool("permissions"),
                        eventPush = caps.bool("eventPush"),
                        commands = caps.bool("commands"),
                        skills = caps.bool("skills"),
                        sessionExport = caps.bool("sessionExport"),
                        configSync = caps.bool("configSync"),
                    ),
                )
            } catch (e: Exception) {
                // A stale discovery file (reused port, dead pid) must not leak the
                // socket + blocked reader thread — the manager retries every 3s.
                runCatching { socket.close() }
                throw e
            }
            return client
        }

        private fun JsonObject?.bool(key: String): Boolean =
            this?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        private fun JsonObject.str(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    @Volatile
    var handshake: BridgeHandshake? = null; private set

    @Volatile
    var onEvent: (JsonObject) -> Unit = {}

    @Volatile
    var onClose: () -> Unit = {}

    private val writer: Writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
    private val timeoutScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "dsh-bridge-timeouts").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject?>>()
    private val nextId = AtomicLong(1)
    @Volatile
    var isOpen = true; private set

    override val capabilities: BridgeCapabilities? get() = handshake?.capabilities

    private fun startReader() {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    dispatch(line)
                }
            } catch (_: Exception) {
                // closed
            } finally {
                handleClose()
            }
        }, "dsh-bridge-reader").apply { isDaemon = true; start() }
    }

    private fun dispatch(line: String) {
        val msg = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return
        }
        val id = msg.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        val f = id?.let { pending.remove(it) }
        if (f != null) {
            val err = msg.get("error") as? JsonObject
            if (err != null) {
                val dataCode = (err.get("data") as? JsonObject)
                    ?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                f.completeExceptionally(
                    BridgeException(
                        err.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "bridge error",
                        err.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -32000,
                        dataCode,
                    )
                )
            } else {
                f.complete(msg.get("result") as? JsonObject)
            }
        } else if (msg.get("method")?.takeIf { it.isJsonPrimitive }?.asString == "bridge.event") {
            runCatching { onEvent(msg.get("params") as? JsonObject ?: JsonObject()) }
        }
    }

    private val closeHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Settle every in-flight request and notify once, whoever closes first. */
    private fun handleClose() {
        isOpen = false
        if (!closeHandled.compareAndSet(false, true)) return
        runCatching { socket.close() }
        timeoutScheduler.shutdownNow()
        failPending()
        runCatching { onClose() }
    }

    private fun failPending() {
        val e = BridgeException("bridge connection closed", -32000, null)
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(e) }
    }

    override fun request(method: String, params: JsonObject?, timeoutMs: Long?): CompletableFuture<JsonObject?> {
        if (!isOpen) {
            return CompletableFuture<JsonObject?>().also {
                it.completeExceptionally(BridgeException("bridge connection closed", -32000, null))
            }
        }
        val id = nextId.getAndIncrement()
        val f = CompletableFuture<JsonObject?>()
        pending[id] = f
        val timeout = timeoutMs?.let { delay ->
            timeoutScheduler.schedule({
                if (pending.remove(id, f)) {
                    f.completeExceptionally(BridgeException("bridge request timed out", -32000, "bridge/timeout"))
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
        f.whenComplete { _, _ ->
            pending.remove(id, f)
            timeout?.cancel(false)
        }
        if (!isOpen) {
            // Closed between the check and the registration (see close()).
            failPending()
            return f
        }
        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params ?: JsonObject())
            addProperty("token", token)
        }
        try {
            synchronized(writer) {
                writer.write(gson.toJson(frame))
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            f.completeExceptionally(e)
        }
        return f
    }

    /**
     * Local close. Callers (reconnect swap, teardown) replace this client, so
     * onClose is not fired — but in-flight requests must still fail instead
     * of hanging until their timeouts (they used to: isOpen=false made the
     * reader's handleClose() bail out early).
     */
    fun close() {
        isOpen = false
        closeHandled.set(true)
        runCatching { socket.close() }
        timeoutScheduler.shutdownNow()
        failPending()
    }
}
