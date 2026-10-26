package com.dshlite.idea.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ProcessTreeTest {
    @Test fun `close terminates both a launcher and its child process`() {
        val directory = Files.createTempDirectory("dsh-process-tree-")
        val source = directory.resolve("TreeSpawn.java")
        val className = "TreeSpawn"
        source.toFile().writeText(
            """
            public class $className {
                public static void main(String[] args) throws Exception {
                    if (args[1].equals("child")) {
                        Thread.sleep(60_000);
                        return;
                    }
                    String java = System.getProperty("java.home") + "/bin/java" +
                        (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
                    new ProcessBuilder(java, args[0], "child").start();
                    Thread.sleep(60_000);
                }
            }
            """.trimIndent()
        )
        val java = System.getProperty("java.home") + "/bin/java" +
            if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val process = ProcessBuilder(java, source.toString(), source.toString(), "root")
            .redirectErrorStream(true)
            .start()
        var child: ProcessHandle? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline && child == null && process.isAlive) {
                child = process.toHandle().descendants().use { stream ->
                    stream.filter { handle ->
                        val arguments = handle.info().arguments().orElse(null)
                        arguments == null || arguments.contains("child")
                    }.findFirst().orElse(null)
                }
                if (child == null) Thread.sleep(50)
            }
            val diagnostic = if (!process.isAlive) process.inputStream.readBytes().toString(Charsets.UTF_8) else ""
            assertNotNull("launcher did not create its child: $diagnostic", child)
            terminateProcessTree(process, graceMs = 100)
            assertFalse("launcher is still alive", process.isAlive)
            assertFalse("child is still alive", child!!.isAlive)
        } finally {
            child?.destroyForcibly()
            destroyProcessTree(process, forcibly = true)
            process.destroyForcibly()
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }
}
