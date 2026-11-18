package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionUpdateData
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChangedFilesTrackerTest {
    @Test fun `extractDiffPaths reads only ACP tool diff content`() {
        val update = SessionUpdateData(
            kind = "tool_call",
            content = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "diff")
                    addProperty("path", "/a/b.ts")
                })
                add(JsonObject().apply { addProperty("type", "content") })
                add(JsonObject().apply {
                    addProperty("type", "diff")
                    addProperty("path", "/a/c.ts")
                })
                add(JsonObject().apply {
                    addProperty("type", "diff")
                    addProperty("path", 42)
                })
            },
        )
        assertEquals(listOf("/a/b.ts", "/a/c.ts"), extractDiffPaths(update))
        assertEquals(emptyList<String>(), extractDiffPaths(SessionUpdateData("agent_message_chunk")))
        assertEquals(emptyList<String>(), extractDiffPaths(SessionUpdateData("tool_call_update")))
    }

    @Test fun `porcelain z consumes rename origin and recognizes creation`() {
        val output = " M src/a.ts\u0000?? src/new.ts\u0000R  src/renamed.ts\u0000src/old.ts\u0000" +
            "C  src/copied.ts\u0000src/source.ts\u0000"
        assertEquals(
            listOf(
                PorcelainEntry("src/a.ts", false),
                PorcelainEntry("src/new.ts", true),
                PorcelainEntry("src/renamed.ts", false),
                PorcelainEntry("src/copied.ts", false),
            ),
            parsePorcelainZ(output),
        )
        assertEquals(emptyList<PorcelainEntry>(), parsePorcelainZ(""))
    }

    @Test fun `ignored paths use complete segments and keep gitignore lookalikes`() {
        for (path in listOf(
            "/w/.git/config", ".git/config", "/w/.git", ".git",
            "C:\\w\\node_modules\\x\\y.js", "node_modules/x.js",
            "/w/dist/app.js", "/w/out/app.js", "/w/.vscode-test/runner.js",
            "/w/docs/debug.md.git", "scratch.git",
        )) assertTrue(path, isIgnoredPath(path))
        for (path in listOf(
            "/w/src/a.ts", "/w/.gitignore", "/w/.gitattributes",
            "/w/debug.md.gitignore", "/w/distinct/a.ts", "/w/outcome/a.ts",
        )) assertTrue(path, !isIgnoredPath(path))
    }

    @Test fun `busy file events are attributed per session and creation is sticky`() {
        val root = Files.createTempDirectory("dsh-changes-vfs-")
        val tracker = tracker(root)
        try {
            val file = root.resolve("src/example.ts")
            tracker.startSession("one")
            tracker.startSession("two")
            tracker.recordFileEvent(file.toString(), ChangedFileKind.CREATED)
            assertEquals(emptyList<ChangedFile>(), tracker.filesFor("one"))

            tracker.promptStarted("one")
            tracker.recordFileEvent(file.toString(), ChangedFileKind.CREATED)
            assertEquals(ChangedFileKind.CREATED, tracker.filesFor("one").single().kind)
            assertEquals(emptyList<ChangedFile>(), tracker.filesFor("two"))

            tracker.promptStarted("two")
            tracker.recordFileEvent(file.toString(), ChangedFileKind.MODIFIED)
            assertEquals(ChangedFileKind.CREATED, tracker.filesFor("one").single().kind)
            assertEquals(ChangedFileKind.MODIFIED, tracker.filesFor("two").single().kind)

            tracker.promptSettled("one")
            tracker.recordFileEvent(root.resolve("later.ts").toString(), ChangedFileKind.MODIFIED)
            assertEquals(listOf(file.toString()), tracker.filesFor("one").map { it.path })
            assertEquals(setOf(file.toString(), root.resolve("later.ts").toString()), tracker.filesFor("two").map { it.path }.toSet())
        } finally {
            tracker.dispose()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `final git snapshot keeps only net changes and filters baseline dirt`() {
        val root = makeRepo()
        val tracker = tracker(root)
        try {
            val tracked = root.resolve("tracked.ts")
            val preDirty = root.resolve("pre-dirty.ts")
            val baselineUntracked = root.resolve("baseline-untracked.ts")
            Files.writeString(preDirty, "already dirty\n")
            Files.writeString(baselineUntracked, "already untracked\n")
            tracker.startSession("session")

            val reverted = root.resolve("reverted.ts")
            val created = root.resolve("created.ts")
            for (path in listOf(
                tracked,
                reverted,
                created,
                root.resolve("docs/debug.md.git"),
                root.resolve(".git/agent-scratch"),
                root.resolve("node_modules/package/index.js"),
            )) tracker.ingestUpdate("session", diffUpdate(path))
            assertEquals(setOf(tracked, reverted, created), tracker.filesFor("session").map { Path.of(it.path) }.toSet())

            Files.writeString(tracked, "final\n")
            Files.writeString(reverted, "temporary\n")
            Files.delete(reverted)
            Files.createDirectories(root.resolve("docs"))
            Files.writeString(root.resolve("docs/debug.md.git"), "scratch\n")
            Files.writeString(created, "new\n")
            Files.writeString(preDirty, "changed again\n")
            tracker.promptSettled("session")

            assertEquals(
                listOf(
                    ChangedFile(created.toString(), "created.ts", ChangedFileKind.CREATED),
                    ChangedFile(tracked.toString(), "tracked.ts", ChangedFileKind.MODIFIED),
                ),
                tracker.filesFor("session"),
            )

            Files.delete(created)
            Files.writeString(tracked, "original\n")
            tracker.promptSettled("session")
            assertEquals(emptyList<ChangedFile>(), tracker.filesFor("session"))
        } finally {
            tracker.dispose()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `failed final git status preserves live ACP results`() {
        val root = makeRepo()
        val tracker = tracker(root)
        try {
            tracker.startSession("session")
            val tracked = root.resolve("tracked.ts")
            tracker.ingestUpdate("session", diffUpdate(tracked))
            root.resolve(".git").toFile().deleteRecursively()
            tracker.promptSettled("session")
            assertEquals(listOf(tracked.toString()), tracker.filesFor("session").map { it.path })
        } finally {
            tracker.dispose()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `clear prevents an in flight git snapshot from writing back`() {
        val root = Files.createTempDirectory("dsh-changes-clear-")
        val executor = Executors.newSingleThreadExecutor()
        val finalStarted = CountDownLatch(1)
        val releaseFinal = CountDownLatch(1)
        val statusCalls = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<List<ChangedFile>>())
        val runner = GitRunner { _, arguments, _ ->
            when (arguments.first()) {
                "rev-parse" -> "true\n".toByteArray()
                "status" -> {
                    if (statusCalls.incrementAndGet() == 1) {
                        ByteArray(0)
                    } else {
                        finalStarted.countDown()
                        releaseFinal.await(5, TimeUnit.SECONDS)
                        "?? late.ts\u0000".toByteArray()
                    }
                }
                else -> null
            }
        }
        val tracker = ChangedFilesTracker(root, { it }, runner, Executor(executor::execute)) { _, files ->
            events.add(files)
        }
        try {
            tracker.startSession("session")
            tracker.promptStarted("session")
            tracker.promptSettled("session")
            assertTrue("final status did not start", finalStarted.await(5, TimeUnit.SECONDS))
            tracker.clear("session")
            assertEquals(emptyList<ChangedFile>(), tracker.filesFor("session"))
            val eventCount = events.size
            releaseFinal.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(emptyList<ChangedFile>(), tracker.filesFor("session"))
            assertEquals("late status must not emit or resurrect the session", eventCount, events.size)
        } finally {
            releaseFinal.countDown()
            tracker.dispose()
            executor.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    private fun tracker(root: Path): ChangedFilesTracker =
        ChangedFilesTracker(root, { it }, ProcessGitRunner, Executor { it.run() }) { _, _ -> }

    private fun diffUpdate(path: Path): SessionUpdateData = SessionUpdateData(
        kind = "tool_call",
        content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "diff")
                addProperty("path", path.toString())
            })
        },
    )

    private fun makeRepo(): Path {
        val root = Files.createTempDirectory("dsh-changes-git-")
        git(root, "init", "--quiet")
        git(root, "config", "user.email", "tests@example.invalid")
        git(root, "config", "user.name", "DSH Tests")
        Files.writeString(root.resolve("tracked.ts"), "original\n")
        Files.writeString(root.resolve("pre-dirty.ts"), "original\n")
        git(root, "add", "tracked.ts", "pre-dirty.ts")
        git(root, "commit", "--quiet", "-m", "baseline")
        return root
    }

    private fun git(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("git ${args.joinToString(" ")}: $output", process.waitFor(15, TimeUnit.SECONDS))
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.exitValue())
    }
}
