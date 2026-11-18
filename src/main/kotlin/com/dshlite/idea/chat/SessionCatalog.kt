package com.dshlite.idea.chat

import com.dshlite.idea.acp.SessionInfoData
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Bridge metadata enrichment, independent of Swing. ACP owns ordinary root
 * membership; the bridge only adds titles/archive metadata and subagent rows.
 */
class SessionCatalog(
    private val request: (String, JsonObject?) -> CompletableFuture<JsonObject?>,
    private val workspace: String,
    private val ignoreCase: Boolean = false,
    private val knownTitles: Map<String, String> = emptyMap(),
) {
    /** Legacy bridge-only listing, retained for older callers and tests. */
    fun fetch(archived: Set<String>): CompletableFuture<List<SessionInfoData>> =
        fetchGroups(archived, null).thenApply { it.sessions }

    fun fetchGroups(archived: Set<String>, parentSessionId: String?): CompletableFuture<SessionGroups> =
        list(archived).thenCompose { rows ->
            val groups = partitionSessions(rows)
            hydrate(groups, parentSessionId)
        }

    /** Enrich exactly [roots]; bridge-only root rows can never enter history. */
    fun enrich(
        roots: List<SessionInfoData>,
        archived: Set<String>,
        parentSessionId: String?,
        onMetadata: (SessionGroups) -> Unit = {},
    ): CompletableFuture<SessionGroups> = list(archived).thenCompose { rows ->
        val titles = rows.filter { !it.title.isNullOrBlank() }.associate { it.sessionId to it.title }
        val titledRoots = roots.map { row ->
            row.copy(title = titles[row.sessionId] ?: row.title ?: knownTitles[row.sessionId])
        }
        val groups = SessionGroups(titledRoots, partitionSessions(rows).subagents)
        // Publish cheap list metadata before any session.get deadline can stall
        // the UI. The returned future completes after background title hydration.
        onMetadata(groups)
        hydrate(groups, parentSessionId)
    }

    private fun list(archived: Set<String>): CompletableFuture<List<SessionInfoData>> =
        request("session.list", JsonObject().apply { addProperty("includeStored", true) })
            .orTimeout(15, TimeUnit.SECONDS).thenApply { response ->
                val rows = LinkedHashMap<String, SessionInfoData>()
                for (key in listOf("sessions", "stored")) {
                    response?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                        val obj = el as? JsonObject ?: return@forEach
                        val parsed = SessionInfoData.parse(obj)
                        val row = parsed.copy(
                            title = parsed.title?.trim()?.takeIf { it.isNotEmpty() } ?: knownTitles[parsed.sessionId],
                        )
                        if (row.sessionId.isBlank() || row.sessionId in archived || !matches(row.cwd)) return@forEach
                        // Live rows take precedence over header-only stored rows.
                        rows.putIfAbsent(row.sessionId, row)
                    }
                }
                rows.values.toList()
            }

    private fun matches(cwd: String?): Boolean =
        cwd == null || cwd.trimEnd('/', '\\').equals(workspace.trimEnd('/', '\\'), ignoreCase)

    /** Hidden nested/foreign children do not cause title RPCs. */
    private fun hydrate(groups: SessionGroups, parentSessionId: String?): CompletableFuture<SessionGroups> {
        val visible = groups.sessions.map { it.sessionId }.toSet() +
            directSubagents(groups.subagents, parentSessionId).map { it.sessionId }
        val rows = groups.sessions + groups.subagents
        return hydrateRows(rows.filter { it.sessionId in visible }, 0).thenApply { titledVisible ->
            val titles = titledVisible.associate { it.sessionId to it.title }
            SessionGroups(
                groups.sessions.map { it.copy(title = it.title ?: titles[it.sessionId]) },
                groups.subagents.map { it.copy(title = it.title ?: titles[it.sessionId]) },
            )
        }
    }

    /** At most eight title lookups in flight, preserving server ordering. */
    private fun hydrateRows(rows: List<SessionInfoData>, offset: Int): CompletableFuture<List<SessionInfoData>> {
        if (offset >= rows.size) return CompletableFuture.completedFuture(emptyList())
        val batch = rows.drop(offset).take(8).map { row ->
            if (!row.title.isNullOrBlank()) CompletableFuture.completedFuture(row)
            else request("session.get", JsonObject().apply { addProperty("sessionId", row.sessionId) })
                .orTimeout(5, TimeUnit.SECONDS).handle { response, _ ->
                    val title = response?.get("title")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
                    row.copy(title = title?.takeIf { it.isNotEmpty() } ?: row.title)
                }
        }
        return CompletableFuture.allOf(*batch.toTypedArray()).thenCompose {
            hydrateRows(rows, offset + batch.size).thenApply { rest -> batch.map { it.join() } + rest }
        }
    }
}
