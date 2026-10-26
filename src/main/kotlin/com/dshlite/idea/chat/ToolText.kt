package com.dshlite.idea.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Pure text helpers for tool-call cards (ports of dsh-lite-vscode webview/state.ts). */
object ToolText {
    private val SUBTITLE_KEYS = listOf(
        "description", "path", "filePath", "file_path", "absPath", "targetFile", "target_file",
        "query", "pattern", "url", "uri", "command", "cmd", "prompt",
    )
    private const val MAX_XML_CHARS = 100_000

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

    /**
     * A result made only of `<k>v</k>` elements renders as key/value rows.
     * This deliberately small parser consumes the input once from left to
     * right; a backtracking regex can rescan the whole suffix for every '<k>'
     * in malformed tool output on the EDT.
     */
    fun xmlRows(detail: String): List<Pair<String, String>>? {
        val trimmed = detail.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_XML_CHARS) return null
        val rows = ArrayList<Pair<String, String>>()
        var pos = 0
        while (pos < trimmed.length) {
            while (pos < trimmed.length && trimmed[pos].isWhitespace()) pos++
            if (pos == trimmed.length) break
            if (trimmed[pos] != '<') return null
            val keyStart = pos + 1
            if (keyStart >= trimmed.length ||
                !(trimmed[keyStart].isLetter() || trimmed[keyStart] == '_')
            ) return null
            var cursor = keyStart + 1
            while (cursor < trimmed.length &&
                (trimmed[cursor].isLetterOrDigit() || trimmed[cursor] == '_' || trimmed[cursor] == '-')
            ) cursor++
            if (cursor >= trimmed.length || trimmed[cursor] != '>') return null
            val key = trimmed.substring(keyStart, cursor)
            val valueStart = cursor + 1
            val closing = "</$key>"
            val valueEnd = trimmed.indexOf(closing, startIndex = valueStart)
            if (valueEnd < 0) return null
            rows.add(key to trimmed.substring(valueStart, valueEnd).trim())
            pos = valueEnd + closing.length
        }
        return rows.ifEmpty { null }
    }
}
