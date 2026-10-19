package com.dshlite.idea.chat

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
}
