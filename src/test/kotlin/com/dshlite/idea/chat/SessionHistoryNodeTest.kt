package com.dshlite.idea.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Executes the real target-side reader when Node with zstd support is available. */
class SessionHistoryNodeTest {
    @Test fun `node reader decodes every zstd frame`() {
        assumeTrue(nodeSupportsZstd)
        val dir = Files.createTempDirectory("dsh-node-history-")
        try {
            val output = dir.resolve("session.v2.jsonl.zstd")
            val fixture = runNode(
                """
                const fs = require('fs'), zlib = require('zlib');
                const event = text => JSON.stringify({type:'user/message',data:{source:{kind:'user'},content:[{type:'text',text}]}}) + '\n';
                const frames = [zlib.zstdCompressSync(event('first')), zlib.zstdCompressSync(event('second'))];
                fs.writeFileSync(process.argv[2], Buffer.concat(frames));
                """.trimIndent(),
                output.toString(),
            )
            assertEquals(fixture.output, 0, fixture.exit)

            val result = runNode(SessionHistory.NODE_READER, dir.toString())
            assertEquals(result.output, 0, result.exit)
            assertTrue(result.output.contains("first"))
            assertTrue(result.output.contains("second"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `node reader rejects oversized plain and decompressed logs`() {
        assumeTrue(nodeSupportsZstd)
        val dir = Files.createTempDirectory("dsh-node-history-limit-")
        try {
            val plain = dir.resolve("session.jsonl")
            java.io.RandomAccessFile(plain.toFile(), "rw").use { it.setLength(33L * 1024 * 1024) }
            val plainResult = runNode(SessionHistory.NODE_READER, dir.toString())
            assertNotEquals(0, plainResult.exit)
            assertTrue(plainResult.output, plainResult.output.contains("32 MiB"))

            Files.delete(plain)
            val bomb = dir.resolve("session.jsonl.zstd")
            val fixture = runNode(
                "const fs=require('fs'),zlib=require('zlib');" +
                    "fs.writeFileSync(process.argv[2],zlib.zstdCompressSync(Buffer.alloc(33*1024*1024,32)));",
                bomb.toString(),
            )
            assertEquals(fixture.output, 0, fixture.exit)
            val bombResult = runNode(SessionHistory.NODE_READER, dir.toString())
            assertNotEquals(0, bombResult.exit)
            assertTrue(bombResult.output, bombResult.output.contains("32 MiB"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private data class Result(val exit: Int, val output: String)

    private fun runNode(script: String, vararg arguments: String): Result {
        val process = ProcessBuilder(listOf("node", "-") + arguments)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val exited = process.waitFor(20, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return Result(if (exited) process.exitValue() else -1, output)
    }

    private companion object {
        val nodeSupportsZstd: Boolean by lazy {
            runCatching {
                val process = ProcessBuilder(
                    "node", "-e",
                    "if (typeof require('zlib').zstdDecompressSync !== 'function') process.exit(1)",
                ).start()
                process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }
    }
}
