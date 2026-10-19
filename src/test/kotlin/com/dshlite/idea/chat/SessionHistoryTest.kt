package com.dshlite.idea.chat

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionHistoryTest {
    @Test fun `restores nested and direct tool results while ignoring injected prompts`() {
        val log = """
            {"type":"user/message","data":{"source":{"kind":"plugin"},"content":[{"type":"text","text":"hidden context"}]}}
            {"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"hello"}]}}
            invalid JSON
            {"type":"assistant/message","data":{"message":{"content":[{"type":"reasoning","text":"a"},{"type":"reasoning","text":"b"},{"type":"text","text":"reply"}]}}}
            {"type":"tool/call","data":{"callId":"old","name":"bash","arguments":"{\"command\":\"pwd\"}"}}
            {"type":"tool/result","data":{"message":{"content":[{"type":"tool-result","toolCallId":"old","content":[{"type":"text","text":"/workspace"}]}]}}}
            {"type":"tool/call","data":{"callId":"new","name":"read_file","arguments":"bad json"}}
            {"type":"tool/result","data":{"message":{"role":"tool","toolCallId":"new","isError":true,"content":[{"type":"text","text":"missing file"}]}}}
        """.trimIndent()
        val items = SessionHistory.parse(log)
        assertEquals(5, items.size)
        assertEquals(HistoryMessage.User("hello"), items[0])
        assertEquals(HistoryMessage.Thought("ab"), items[1])
        assertEquals(HistoryMessage.Assistant("reply"), items[2])
        val old = items[3] as HistoryMessage.Tool
        assertEquals("completed", old.status)
        assertEquals("pwd", old.subtitle)
        assertEquals("/workspace", old.detail)
        val new = items[4] as HistoryMessage.Tool
        assertEquals("failed", new.status)
        assertEquals("missing file", new.detail)
        assertEquals("bad json", new.input!!.asString)
    }

    @Test fun `imports only root log from archive and preserves the archive`() {
        val file = Files.createTempFile("dsh-history-", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in listOf("children/session.v4.jsonl", "session.v4.jsonl", "attachments/a.txt")) {
                    zip.putNextEntry(ZipEntry(name))
                    val text = if (name.startsWith("session.")) "root" else "child"
                    zip.write("""{"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}}""".toByteArray())
                    zip.closeEntry()
                }
            }
            assertEquals(listOf(HistoryMessage.User("root")), SessionHistory.parseArchive(file))
            assertTrue(file.isFile)
        } finally { file.delete() }
    }

    @Test fun `limits decompressed log size as well as archive size`() {
        val file = Files.createTempFile("dsh-history-large-", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("session.v4.jsonl"))
                val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
                repeat(33) { zip.write(block) }
                zip.closeEntry()
            }
            assertTrue(file.length() < 1024 * 1024)
            assertThrows(IllegalArgumentException::class.java) { SessionHistory.parseArchive(file) }
        } finally { file.delete() }
    }

    @Test fun `rejects shell and glob metacharacters in session ids`() {
        for (id in listOf("*", ".", "..", "-danger", "a/b", "a'", "a;id", "\$(id)", "a\nb", "")) {
            assertFalse(id, SessionIds.isSafe(id))
            assertNull(SessionHistory.shellScript(id))
        }
        for (id in listOf("123abc", "session-1234-abcd")) assertNotNull(SessionHistory.shellScript(id))
    }

    @Test fun `tool helpers preserve mixed output and prefer descriptions`() {
        val input = JsonParser.parseString("""{"command":"pwd","description":"检查目录\nextra"}""")
        assertEquals("检查目录", ToolText.subtitle(input))
        assertEquals(listOf("exit" to "0"), ToolText.xmlRows("<exit>0</exit>"))
        assertNull(ToolText.xmlRows("prefix<exit>0</exit>"))
        assertNull(ToolText.xmlRows("<exit>0</exit>suffix"))
    }
}
