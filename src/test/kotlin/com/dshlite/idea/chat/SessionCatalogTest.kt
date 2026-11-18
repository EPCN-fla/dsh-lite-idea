package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SessionCatalogTest {
    @Test fun `filters archived and foreign rows and keeps live titles`() {
        val hydrated = ArrayList<String>()
        val listing = JsonParser.parseString("""{
            "sessions":[{"sessionId":"a","cwd":"/workspace","title":"Live"}],
            "stored":[{"sessionId":"a","cwd":"/workspace"},
                      {"sessionId":"b","cwd":"/workspace/"},
                      {"sessionId":"archived","cwd":"/workspace"},
                      {"sessionId":"foreign","cwd":"/other"},
                      {"sessionId":"case-sensitive","cwd":"/Workspace"},
                      {"sessionId":""}]
        }""").asJsonObject
        val catalog = SessionCatalog({ method, params ->
            val response = if (method == "session.list") listing else {
                hydrated.add(params!!.get("sessionId").asString)
                JsonObject().apply { addProperty("title", "Stored") }
            }
            CompletableFuture.completedFuture(response)
        }, "/workspace")
        val rows = catalog.fetch(setOf("archived")).join()
        assertEquals(listOf("a", "b"), rows.map { it.sessionId })
        assertEquals(listOf("Live", "Stored"), rows.map { it.title })
        assertEquals(listOf("b"), hydrated)
    }

    @Test fun `title requests are bounded and preserve ordering across failed requests`() {
        val pending = LinkedHashMap<String, CompletableFuture<JsonObject?>>()
        val listing = JsonObject().apply {
            add("stored", com.google.gson.JsonArray().apply {
                repeat(19) { i -> add(JsonObject().apply { addProperty("sessionId", "s$i") }) }
            })
        }
        val result = SessionCatalog({ method, params ->
            if (method == "session.list") CompletableFuture.completedFuture(listing)
            else CompletableFuture<JsonObject?>().also { pending[params!!.get("sessionId").asString] = it }
        }, "/workspace").fetch(emptySet())
        assertEquals(8, pending.size)
        for (offset in listOf(0, 8, 16)) {
            val batch = pending.entries.toList().drop(offset).take(8)
            for ((id, future) in batch) {
                if (id == "s3") future.completeExceptionally(IllegalStateException("missing"))
                else future.complete(JsonObject().apply { addProperty("title", "Title $id") })
            }
            assertEquals(minOf(offset + 16, 19), pending.size)
        }
        val rows = result.join()
        assertEquals((0..18).map { "s$it" }, rows.map { it.sessionId })
        assertNull(rows[3].title)
        assertEquals("Title s18", rows[18].title)
    }
    @Test fun `bridge enriches ACP roots without injecting roots and exposes only direct children`() {
        val listing = JsonParser.parseString("""{
            "sessions":[
              {"sessionId":"root","cwd":"/workspace","title":"Bridge title"},
              {"sessionId":"bridge-only","cwd":"/workspace","title":"Injected"},
              {"sessionId":"child","cwd":"/workspace","origin":"subagent","parentSession":"root","live":true},
              {"sessionId":"fork","cwd":"/workspace","parentSession":"root"}
            ],
            "stored":[
              {"sessionId":"nested","cwd":"/workspace","origin":"subagent","parentSession":"child"},
              {"sessionId":"foreign","cwd":"/workspace","origin":"subagent","parentSession":"other"},
              {"sessionId":"stored-child","cwd":"/workspace","origin":"subagent","parentSession":"root"}
            ]
        }""").asJsonObject
        val catalog = SessionCatalog({ method, params ->
            if (method == "session.list") CompletableFuture.completedFuture(listing)
            else CompletableFuture.completedFuture(JsonObject().apply { addProperty("title", "Hydrated") })
        }, "/workspace")
        val roots = listOf(
            SessionInfoData("root", "/workspace", null, null, live = true),
            SessionInfoData("fork", "/workspace", null, null),
        )
        val groups = catalog.enrich(roots, emptySet(), "root").join()
        assertEquals(listOf("root", "fork"), groups.sessions.map { it.sessionId })
        assertEquals("Bridge title", groups.sessions[0].title)
        assertEquals(listOf("child", "stored-child"), directSubagents(groups.subagents, "root").map { it.sessionId })
        assertFalse(groups.sessions.any { it.sessionId == "bridge-only" })
    }

    @Test fun `hidden children are not hydrated and archived children stay hidden`() {
        val hydrated = ArrayList<String>()
        val listing = JsonParser.parseString("""{
            "stored":[
              {"sessionId":"child","origin":"subagent","parentSession":"root"},
              {"sessionId":"nested","origin":"subagent","parentSession":"child"},
              {"sessionId":"archived","origin":"subagent","parentSession":"root"}
            ]
        }""").asJsonObject
        val groups = SessionCatalog({ method, params ->
            if (method == "session.list") CompletableFuture.completedFuture(listing)
            else {
                hydrated += params!!.get("sessionId").asString
                CompletableFuture.completedFuture(JsonObject().apply { addProperty("title", "Child") })
            }
        }, "/workspace").fetchGroups(setOf("archived"), "root").join()
        assertEquals(listOf("child"), directSubagents(groups.subagents, "root").map { it.sessionId })
        assertEquals(listOf("child"), hydrated)
    }

    @Test fun `metadata snapshot is published before slow title hydration finishes`() {
        val pendingTitle = CompletableFuture<JsonObject?>()
        val listing = JsonParser.parseString("""{
            "sessions":[{"sessionId":"root","cwd":"/workspace","title":"From bridge"}],
            "stored":[{"sessionId":"child","cwd":"/workspace","origin":"subagent","parentSession":"root"}]
        }""").asJsonObject
        val snapshots = ArrayList<com.dshlite.idea.chat.SessionGroups>()
        val result = SessionCatalog({ method, _ ->
            if (method == "session.list") CompletableFuture.completedFuture(listing) else pendingTitle
        }, "/workspace").enrich(
            roots = listOf(SessionInfoData("root", "/workspace", null, null, live = true)),
            archived = emptySet(),
            parentSessionId = "root",
            onMetadata = { snapshots += it },
        )

        assertEquals(1, snapshots.size)
        assertEquals("From bridge", snapshots.single().sessions.single().title)
        assertEquals(listOf("child"), directSubagents(snapshots.single().subagents, "root").map { it.sessionId })
        assertFalse(result.isDone)

        pendingTitle.complete(JsonObject().apply { addProperty("title", "Child title") })
        assertEquals("Child title", directSubagents(result.join().subagents, "root").single().title)
    }

}
