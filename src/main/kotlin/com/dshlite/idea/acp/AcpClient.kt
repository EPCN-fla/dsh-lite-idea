/**
 * Minimal ACP JSON-RPC client over newline-delimited JSON on stdio.
 *
 * One process + one connection multiplexes many sessions. A reader thread
 * dispatches responses, notifications and incoming requests. Threading: all
 * callbacks fire on the reader thread; callers marshal to the EDT as needed.
 */
package com.dshlite.idea.acp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AcpException(message: String) : Exception(message)

interface AcpClientListener {
    /** Every session/update notification. Called on the reader thread. */
    fun onSessionUpdate(sessionId: String, update: SessionUpdateData)

    /** Permission prompt from the agent; must settle exactly once. Called on the reader thread. */
    fun onPermission(request: PermissionRequestData): CompletableFuture<String?> // null => cancelled

    fun onExit(code: Int, stderrTail: String) {}
}

class AcpClient private constructor(
    private val process: Process,
    private val listener: AcpClientListener,
) {
    companion object {
        private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
        const val PROTOCOL_VERSION = 1
        private const val CLIENT_NAME = "dsh-idea"

        /** Start the agent process and run the initialize handshake. */
        fun start(
            process: Process,
            clientVersion: String,
            listener: AcpClientListener,
            timeoutMs: Long = 60_000,
        ): AcpClient {
            val client = AcpClient(process, listener)
            client.startReader()
            val params = JsonObject().apply {
                addProperty("protocolVersion", PROTOCOL_VERSION)
                add("clientCapabilities", JsonObject().apply {
                    add("fs", JsonObject().apply {
                        addProperty("readTextFile", false)
                        addProperty("writeTextFile", false)
                    })
                    addProperty("terminal", false)
                })
                add("clientInfo", JsonObject().apply {
                    addProperty("name", CLIENT_NAME)
                    addProperty("version", clientVersion)
                })
            }
            val init = client.request("initialize", params)
            try {
                val result = init.get(timeoutMs, TimeUnit.MILLISECONDS)
                val info = result?.get("agentInfo") as? JsonObject
                client.agentIdentity = AgentIdentity(info?.str("name"), info?.str("version"))
            } catch (e: Exception) {
                val reason = if (!client.isAlive()) "process exited during startup (code=${client.exitCode()})"
                else "no response within ${timeoutMs / 1000}s"
                val tail = client.stderrTail().trim().takeLast(600)
                client.close()
                throw AcpException(
                    "dsh ACP startup failed: $reason." +
                        (if (tail.isNotEmpty()) "\n--- stderr ---\n$tail" else "")
                )
            }
            return client
        }
    }

    var agentIdentity: AgentIdentity? = null; private set

    private val writer: Writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject?>>()
    private val nextId = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private val stderrTail = StringBuilder()
    private val readerDone = java.util.concurrent.CountDownLatch(1)

    fun isAlive(): Boolean = !closed.get() && process.isAlive
    fun exitCode(): Int? = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    private fun startReader() {
        // stderr pump
        Thread({
            try {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 8192) stderrTail.delete(0, stderrTail.length - 8192)
                    }
                }
            } catch (_: Exception) {
                // stream closed
            }
        }, "dsh-acp-stderr").apply { isDaemon = true; start() }

        // exit watcher
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            // stdout may still contain the last response/chunks after the
            // process exits. Let the reader drain them before publishing exit.
            readerDone.await(3, TimeUnit.SECONDS)
            close()
            failAll(AcpException("dsh process exited (code=$code)"))
            listener.onExit(code, stderrTail())
        }, "dsh-acp-exit").apply { isDaemon = true; start() }

        // stdout reader (protocol)
        Thread({
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            try {
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    dispatch(line)
                }
            } catch (_: Exception) {
                // closed
            } finally {
                close()
                readerDone.countDown()
            }
        }, "dsh-acp-reader").apply { isDaemon = true; start() }
    }

    fun stderrTail(): String = synchronized(stderrTail) { stderrTail.toString() }

    private fun dispatch(line: String) {
        val msg = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return
        }
        val idEl = msg.get("id")?.takeIf { it.isJsonPrimitive }
        val idNum = idEl?.takeIf { it.asJsonPrimitive.isNumber }?.asLong
        val method = msg.str("method")
        try {
            when {
                // response (our ids are always numeric)
                idNum != null && (msg.has("result") || msg.has("error")) -> {
                    val future = pending.remove(idNum) ?: return
                    val error = msg.get("error") as? JsonObject
                    if (error != null) {
                        future.completeExceptionally(
                            AcpException(acpErrorText(error.str("message"), error.get("data")))
                        )
                    } else {
                        future.complete(msg.get("result") as? JsonObject)
                    }
                }
                // incoming request from agent (echo the id as-is; may be a string)
                idEl != null && method != null -> handleIncomingRequest(idEl, method, msg.get("params") as? JsonObject)
                // notification
                method == "session/update" -> {
                    val params = msg.get("params") as? JsonObject ?: return
                    val sessionId = params.str("sessionId") ?: return
                    val update = params.get("update") as? JsonObject ?: return
                    listener.onSessionUpdate(sessionId, parseSessionUpdate(update))
                }
            }
        } catch (e: Exception) {
            // A throwing listener must not kill the reader while the agent lives.
            com.intellij.openapi.diagnostic.Logger.getInstance(AcpClient::class.java)
                .warn("[dsh] dispatch failed for ${method ?: "response"}", e)
        }
    }

    private fun handleIncomingRequest(id: com.google.gson.JsonElement, method: String, params: JsonObject?) {
        if (method == "session/request_permission" && params != null) {
            val req = PermissionRequestData.parse(params)
            listener.onPermission(req).whenComplete { optionId, _ ->
                val outcome = JsonObject().apply {
                    if (optionId != null) {
                        add("outcome", JsonObject().apply {
                            addProperty("outcome", "selected")
                            addProperty("optionId", optionId)
                        })
                    } else {
                        add("outcome", JsonObject().apply { addProperty("outcome", "cancelled") })
                    }
                }
                respond(id, outcome)
            }
        } else {
            respondError(id, -32601, "Method not found: $method")
        }
    }

    @Synchronized
    private fun write(obj: JsonObject) {
        if (closed.get()) return
        writer.write(gson.toJson(obj))
        writer.write("\n")
        writer.flush()
    }

    private fun respond(id: com.google.gson.JsonElement, result: JsonObject) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        })
    }

    private fun respondError(id: com.google.gson.JsonElement, code: Int, message: String) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        })
    }

    /** Send a request; the future completes with the result object (may be null). */
    fun request(method: String, params: JsonObject): CompletableFuture<JsonObject?> {
        if (closed.get() || !process.isAlive) {
            return CompletableFuture<JsonObject?>().also {
                it.completeExceptionally(AcpException("dsh connection is closed"))
            }
        }
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject?>()
        pending[id] = future
        future.whenComplete { _, _ -> pending.remove(id, future) }
        // The process may have exited between the check above and the
        // registration: failAll() already ran, so nothing would ever settle
        // this future. Re-check after registering.
        if (closed.get() || !process.isAlive) {
            pending.remove(id)
            future.completeExceptionally(AcpException("dsh connection is closed"))
            return future
        }
        try {
            write(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            })
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return future
    }

    fun notify(method: String, params: JsonObject) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        })
    }

    private fun failAll(e: Exception) {
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        failAll(AcpException("dsh connection closed"))
        // writer.close() can block on a full stdin pipe with a hung child —
        // never run this on the EDT (reset() comes from the toolbar).
        Thread({
            // Kill first: close() on a writer whose flush is blocked by a
            // full pipe takes the same lock and otherwise never reaches kill.
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            runCatching { writer.close() }
        }, "dsh-acp-close").apply { isDaemon = true; start() }
    }

    /* --------------------------- convenience --------------------------- */

    private fun sessionParams(sessionId: String) = JsonObject().apply { addProperty("sessionId", sessionId) }

    fun newSession(cwd: String): CompletableFuture<NewSessionData> =
        request("session/new", JsonObject().apply {
            addProperty("cwd", cwd)
            add("mcpServers", com.google.gson.JsonArray())
        }).thenApply { r ->
            NewSessionData(
                sessionId = r?.str("sessionId") ?: throw AcpException("session/new returned no sessionId"),
                configOptions = ConfigOption.parseAll(r?.get("configOptions")),
                currentModeId = (r?.get("modes") as? JsonObject)?.str("currentModeId"),
            )
        }

    fun listSessions(cwd: String?, cursor: String? = null): CompletableFuture<Pair<List<SessionInfoData>, String?>> =
        request("session/list", JsonObject().apply {
            if (cwd != null) addProperty("cwd", cwd)
            if (cursor != null) addProperty("cursor", cursor)
        }).thenApply { r ->
            val sessions = r?.getAsJsonArray("sessions")?.mapNotNull { el ->
                (el as? JsonObject)?.let(SessionInfoData::parse)
            } ?: emptyList()
            sessions to r?.str("nextCursor")
        }

    fun resumeSession(sessionId: String, cwd: String): CompletableFuture<List<ConfigOption>> =
        request("session/resume", JsonObject().apply {
            addProperty("sessionId", sessionId)
            addProperty("cwd", cwd)
            add("mcpServers", com.google.gson.JsonArray())
        }).thenApply { r -> ConfigOption.parseAll(r?.get("configOptions")) }

    fun prompt(sessionId: String, blocks: List<ContentBlock>): CompletableFuture<String> =
        request("session/prompt", sessionParams(sessionId).apply {
            add("prompt", gson.toJsonTree(blocks))
        }).thenApply { r -> r?.str("stopReason") ?: "end_turn" }

    fun cancel(sessionId: String) {
        runCatching { notify("session/cancel", sessionParams(sessionId)) }
    }

    fun setConfigOption(sessionId: String, configId: String, value: String): CompletableFuture<List<ConfigOption>> =
        request("session/set_config_option", sessionParams(sessionId).apply {
            addProperty("configId", configId)
            addProperty("value", value)
        }).thenApply { r -> ConfigOption.parseAll(r?.get("configOptions")) }

    /** Fire-and-forget: never block the caller on a dying agent. */
    fun closeSession(sessionId: String) {
        runCatching { request("session/close", sessionParams(sessionId)) }
    }
}
