package com.dshlite.idea.chat

import com.dshlite.idea.DshBundle
import com.dshlite.idea.bridge.BridgeClient
import com.dshlite.idea.launcher.Launcher
import com.dshlite.idea.launcher.ShellEnv
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** One renderable transcript item rebuilt from a dsh session log. */
sealed class HistoryMessage {
    data class User(val text: String) : HistoryMessage()
    data class Assistant(val text: String) : HistoryMessage()
    data class Thought(val text: String) : HistoryMessage()
    data class Tool(
        val id: String,
        val title: String,
        val subtitle: String?,
        var status: String,
        val input: JsonElement?,
        var detail: String? = null,
    ) : HistoryMessage()
}

/**
 * Rebuild a session's display transcript from its durable dsh log (port of
 * dsh-lite-vscode `src/chat/sessionHistory.ts`). ACP `session/resume` does not
 * replay history, so without this a resumed session renders as an empty chat.
 *
 * Two sources, tried in order:
 *  1. the bridge's `session.exportZip` (flushes a live session first, works
 *     for every log version) — the root `session[.vN].jsonl` in the archive;
 *  2. a target-side read of `$DSH_HOME/sessions/<ws>/<id>/session*.jsonl.zstd`
 *     decoded by node — for setups without the bridge.
 */
object SessionHistory {
    /** Skip pathological exports instead of stalling the UI. */
    private const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
    /** Bound one tool result's rendered text. */
    private const val MAX_RESULT_CHARS = 100_000
    private val ROOT_LOG = Regex("^session(?:\\.v\\d+)?\\.jsonl$")
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.arr(k: String) = get(k)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun textOf(blocks: com.google.gson.JsonArray?): String =
        blocks?.mapNotNull { b ->
            (b as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
        }?.joinToString("") ?: ""

    /**
     * Convert the canonical JSONL of one session log into renderable messages.
     * Pure and format-tolerant: the header, turn/step markers, and unknown
     * event types are skipped.
     */
    fun parse(jsonl: String): List<HistoryMessage> {
        val out = ArrayList<HistoryMessage>()
        val tools = HashMap<String, HistoryMessage.Tool>()
        for (raw in jsonl.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val ev = runCatching { JsonParser.parseString(line) }.getOrNull() as? JsonObject ?: continue
            val type = ev.str("type") ?: continue
            val data = ev.obj("data") ?: continue
            when (type) {
                "user/message" -> {
                    // Injected context (file notices, skills, model switches) is
                    // model-facing only — the user never typed it.
                    if (data.obj("source")?.str("kind") != "user") continue
                    val parts = ArrayList<String>()
                    data.arr("content")?.forEach { el ->
                        val b = el as? JsonObject ?: return@forEach
                        when (b.str("type")) {
                            "text" -> b.str("text")?.let(parts::add)
                            "image" -> parts.add("🖼 " + (b.obj("attachment")?.str("name") ?: DshBundle.message("history.image")))
                            "file" -> {
                                val a = b.obj("attachment")
                                parts.add("📄 " + (a?.str("name") ?: a?.str("fileName") ?: DshBundle.message("history.file")))
                            }
                        }
                    }
                    val text = parts.joinToString("\n").trim()
                    if (text.isNotEmpty()) out.add(HistoryMessage.User(text))
                }
                "assistant/message" -> {
                    data.obj("message")?.arr("content")?.forEach { el ->
                        val b = el as? JsonObject ?: return@forEach
                        val text = b.str("text")
                        if (text.isNullOrEmpty()) return@forEach
                        when (b.str("type")) {
                            "reasoning" -> {
                                // Consecutive reasoning blocks read as one thought.
                                val last = out.lastOrNull()
                                if (last is HistoryMessage.Thought) out[out.size - 1] = HistoryMessage.Thought(last.text + text)
                                else out.add(HistoryMessage.Thought(text))
                            }
                            "text" -> out.add(HistoryMessage.Assistant(text))
                        }
                    }
                }
                "tool/call" -> {
                    val id = data.str("callId") ?: continue
                    val argText = data.str("arguments")
                    val input: JsonElement? = argText?.let {
                        runCatching { JsonParser.parseString(it) }.getOrNull()
                            ?: com.google.gson.JsonPrimitive(it)
                    }
                    val tool = HistoryMessage.Tool(
                        id = id,
                        title = data.str("name") ?: "tool",
                        subtitle = ToolText.subtitle(input),
                        status = "in_progress",
                        input = input,
                    )
                    tools[id] = tool
                    out.add(tool)
                }
                "tool/result" -> {
                    val message = data.obj("message") ?: continue
                    val nested = message.arr("content")?.firstOrNull() as? JsonObject
                    val result = nested?.takeIf { it.str("type") == "tool-result" } ?: message
                    val callId = result.str("toolCallId") ?: message.obj("source")?.str("callId") ?: continue
                    val tool = tools[callId] ?: continue
                    val error = result.get("isError")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    tool.status = if (error?.isBoolean == true && error.asBoolean) "failed" else "completed"
                    val detail = textOf(result.arr("content"))
                    if (detail.isNotEmpty()) {
                        tool.detail = if (detail.length > MAX_RESULT_CHARS) detail.take(MAX_RESULT_CHARS) + "\n" + DshBundle.message("tool.truncated") else detail
                    }
                }
            }
        }
        // A static log cannot still be running: a call without a recorded
        // result was interrupted by a crash/cancel. A later live update can
        // still replace this state through the registered history card.
        out.filterIsInstance<HistoryMessage.Tool>().forEach { tool ->
            if (tool.status == "in_progress") tool.status = "failed"
        }
        return out
    }

    /**
     * Blocking: export through the bridge and parse. Returns null when the
     * archive is missing, oversized, or has no root log.
     */
    fun importViaBridge(client: BridgeClient, launcher: Launcher, sessionId: String): List<HistoryMessage>? {
        val res = client.request("session.exportZip", JsonObject().apply { addProperty("sessionId", sessionId) }, 60_000)
            .get(60, TimeUnit.SECONDS) ?: return null
        val path = res.str("path") ?: return null
        val bytes = res.get("bytes")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        if (bytes > MAX_ARCHIVE_BYTES) return null
        val hostPath = runCatching { launcher.fromDshPath(path) }.getOrDefault(path)
        // The bridge uses the same default path as an explicit user export.
        // This file is not owned by the importer: deleting it also deletes a
        // ZIP the user may have exported for later use.
        return parseArchive(File(hostPath))
    }

    /** Parse the root `session[.vN].jsonl` of an export archive (null if absent). */
    fun parseArchive(file: File): List<HistoryMessage>? {
        // Check the real file before parsing its central directory; bridge-reported
        // bytes are only an early hint and may be absent or stale.
        require(file.isFile) { "Session archive is not a regular file" }
        require(file.length() <= MAX_ARCHIVE_BYTES) { "Session archive exceeds 32 MiB" }
        return ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { ROOT_LOG.matches(it.name) } ?: return null
            require(entry.size <= MAX_ARCHIVE_BYTES) { "Session log exceeds 32 MiB" }
            val bytes = zip.getInputStream(entry).use { it.readNBytes((MAX_ARCHIVE_BYTES + 1).toInt()) }
            require(bytes.size <= MAX_ARCHIVE_BYTES) { "Session log exceeds 32 MiB" }
            parse(bytes.toString(Charsets.UTF_8))
        }
    }

    /**
     * Node program for the shell fallback (fed through stdin, so no quoting
     * survives wsl.exe argument mangling). argv: candidate session dirs.
     * Picks the newest log version, decodes every zstd frame (node's
     * decompressor stops after the first one; a magic-looking byte run inside
     * a frame is skipped by extending the slice until it decodes), and prints
     * only the event types the parser needs.
     */
    internal val NODE_READER = """
        const fs = require('fs'), path = require('path'), zlib = require('zlib');
        const KEEP = new Set(['user/message', 'assistant/message', 'tool/call', 'tool/result']);
        const MAX = $MAX_ARCHIVE_BYTES;
        let found = false;
        for (const dir of process.argv.slice(2)) {
          let names; try { names = fs.readdirSync(dir); } catch (e) { continue; }
          const logs = names.map(n => { const m = /^session(?:\.v(\d+))?\.jsonl(\.zstd)?${'$'}/.exec(n); return m ? { n, v: +(m[1] || 0), z: !!m[2] } : null; })
            .filter(Boolean).sort((a, b) => b.v - a.v);
          if (!logs.length) continue;
          found = true;
          const file = path.join(dir, logs[0].n);
          const stat = fs.statSync(file);
          if (!stat.isFile() || stat.size > MAX) throw new Error('Session log exceeds 32 MiB');
          const buf = fs.readFileSync(file);
          let text = '';
          if (!logs[0].z) text = buf.toString('utf8');
          else {
            if (typeof zlib.zstdDecompressSync !== 'function') throw new Error('Node zstd support is required');
            const at = [];
            const chunks = [];
            let decodedBytes = 0;
            for (let i = 0; i + 4 <= buf.length; i++) if (buf.readUInt32LE(i) === 0xfd2fb528) at.push(i);
            let start = at.length ? at[0] : buf.length;
            for (let k = 1; k <= at.length; k++) {
              const end = k < at.length ? at[k] : buf.length;
              try {
                // One extra byte distinguishes overflow from an exactly-full log.
                const decoded = zlib.zstdDecompressSync(
                  buf.subarray(start, end),
                  { maxOutputLength: MAX - decodedBytes + 1 },
                );
                decodedBytes += decoded.length;
                if (decodedBytes > MAX) throw new Error('Session log exceeds 32 MiB');
                chunks.push(decoded);
                start = end;
              } catch (e) {
                // A magic-looking byte run inside a frame is skipped by extending
                // the slice. Overflow is a hard failure, never a partial import.
                if (e && e.code === 'ERR_BUFFER_TOO_LARGE') throw new Error('Session log exceeds 32 MiB');
                if (e && e.message === 'Session log exceeds 32 MiB') throw e;
              }
            }
            if (!chunks.length) throw new Error('No readable zstd frame in the session log');
            text = Buffer.concat(chunks, decodedBytes).toString('utf8');
          }
          let outputBytes = 0, offset = 0;
          while (offset < text.length) {
            let end = text.indexOf('\n', offset);
            if (end < 0) end = text.length;
            const line = text.substring(offset, end);
            let keep = false;
            if (line.trim()) {
              try { keep = KEEP.has(JSON.parse(line).type); } catch (e) {}
            }
            if (keep) {
              const bytes = Buffer.byteLength(line, 'utf8') + 1;
              outputBytes += bytes;
              if (outputBytes > MAX) throw new Error('Filtered session history exceeds 32 MiB');
              process.stdout.write(line + '\n');
            }
            if (end === text.length) break;
            offset = end + 1;
          }
          break;
        }
        if (!found) throw new Error('Session log not found');
    """.trimIndent()

    /** Target-side shell script for the fallback reader (needs node with zstd, ≥ 22.15). */
    fun shellScript(sessionId: String): String? {
        if (!SessionIds.isSafe(sessionId)) return null
        val b64 = Base64.getEncoder().encodeToString(NODE_READER.toByteArray(Charsets.UTF_8))
        // No $( ) — it does not survive the wsl.exe invocation path.
        return ShellEnv.posixBootstrap() + "; echo " + b64 + " | base64 -d | node - " +
            "\"\${DSH_HOME:-\$HOME/.dsh}/sessions\"/*/" + sessionId
    }
}
