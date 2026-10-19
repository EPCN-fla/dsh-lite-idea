package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Workspace session listing, independent of Swing. The RPC function is
 * injected so merging/filtering and bounded title hydration can be tested
 * without a running IDE or bridge.
 */
class SessionCatalog(
    private val request: (String, JsonObject?) -> CompletableFuture<JsonObject?>,
    private val workspace: String,
    private val ignoreCase: Boolean = false,
) {
    fun fetch(archived: Set<String>): CompletableFuture<List<SessionInfoData>> =
        request("session.list", JsonObject().apply { addProperty("includeStored", true) })
            .orTimeout(15, TimeUnit.SECONDS).thenCompose { response ->
                val rows = LinkedHashMap<String, SessionInfoData>()
                for (key in listOf("sessions", "stored")) {
                    response?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                        val obj = el as? JsonObject ?: return@forEach
                        val row = SessionInfoData.parse(obj)
                        if (row.sessionId.isBlank() || row.sessionId in archived || !matches(row.cwd)) return@forEach
                        // Live rows take precedence over header-only stored rows.
                        rows.putIfAbsent(row.sessionId, row)
                    }
                }
                hydrate(rows.values.toList(), 0)
            }

    private fun matches(cwd: String?): Boolean =
        cwd == null || cwd.trimEnd('/', '\\').equals(workspace.trimEnd('/', '\\'), ignoreCase)

    /** At most eight title lookups in flight, preserving server ordering. */
    private fun hydrate(rows: List<SessionInfoData>, offset: Int): CompletableFuture<List<SessionInfoData>> {
        if (offset >= rows.size) return CompletableFuture.completedFuture(emptyList())
        val batch = rows.drop(offset).take(8).map { row ->
            if (!row.title.isNullOrBlank()) CompletableFuture.completedFuture(row)
            else request("session.get", JsonObject().apply { addProperty("sessionId", row.sessionId) })
                .orTimeout(5, TimeUnit.SECONDS).handle { response, _ ->
                    val title = response?.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    row.copy(title = title ?: row.title)
                }
        }
        return CompletableFuture.allOf(*batch.toTypedArray()).thenCompose {
            hydrate(rows, offset + batch.size).thenApply { rest -> batch.map { it.join() } + rest }
        }
    }
}
