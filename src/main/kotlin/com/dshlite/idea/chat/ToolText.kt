package com.dshlite.idea.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Pure text helpers for tool-call cards (ports of dsh-lite-vscode webview/state.ts). */
object ToolText {
    private val SUBTITLE_KEYS = listOf(
        "description", "path", "filePath", "file_path", "absPath", "targetFile", "target_file",
        "query", "pattern", "url", "uri", "command", "cmd", "prompt",
    )
    private val XML_ROW = Regex("<([a-zA-Z_][\\w-]*)>([\\s\\S]*?)</\\1>")

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /**
     * One-line summary of a tool call's input, like the DSH Web UI rows: the
     * model-written description when present, else the primary target
     * (path / query / url / command).
     */
    fun subtitle(input: JsonElement?): String? {
        val o = input as? JsonObject ?: return null
        for (k in SUBTITLE_KEYS) {
            val s = o.str(k)?.trim()
            if (s.isNullOrEmpty()) continue
            val first = s.lineSequence().first()
            return if (first.length > 120) first.take(117) + "…" else first
        }
        return null
    }

    /** ACP `ToolCallContent[]` → display text (text blocks and diffs). */
    fun detail(content: JsonElement?): String? {
        val arr = content?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val parts = arr.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            when (c.str("type")) {
                "content" -> (c.get("content") as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
                "diff" -> "--- " + (c.str("path") ?: "") + "\n" + (c.str("oldText") ?: "") + "\n+++ \n" + (c.str("newText") ?: "")
                else -> null
            }
        }
        return parts.joinToString("\n\n").ifEmpty { null }
    }

    /** A result made only of `<k>v</k>` elements renders as key/value rows; else null. */
    fun xmlRows(detail: String): List<Pair<String, String>>? {
        val trimmed = detail.trim()
        val rows = ArrayList<Pair<String, String>>()
        var pos = 0
        for (m in XML_ROW.findAll(trimmed)) {
            if (trimmed.substring(pos, m.range.first).isNotBlank()) return null
            rows.add(m.groupValues[1] to m.groupValues[2].trim())
            pos = m.range.last + 1
        }
        if (rows.isEmpty() || trimmed.substring(pos).isNotBlank()) return null
        return rows
    }
}
