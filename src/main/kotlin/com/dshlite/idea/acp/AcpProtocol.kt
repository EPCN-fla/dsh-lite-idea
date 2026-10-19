/**
 * ACP (Agent Client Protocol) wire models — parsed with Gson.
 *
 * Field names intentionally match the JSON wire format (camelCase).
 * Unknown fields are ignored by Gson, keeping the client forward-compatible.
 */
package com.dshlite.idea.acp

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/* ----------------------------- content ----------------------------- */

class ContentBlock {
    var type: String = "text"
    var text: String? = null
    var uri: String? = null
    var name: String? = null
    var mimeType: String? = null

    companion object {
        fun text(t: String) = ContentBlock().apply { type = "text"; text = t }
        fun resourceLink(uri: String, name: String) =
            ContentBlock().apply { type = "resource_link"; this.uri = uri; this.name = name }
    }
}

/* ------------------------- session updates ------------------------- */

/**
 * Flattened view of a `session/update` payload. Only fields relevant to the
 * given [kind] are populated; everything else stays null.
 */
data class SessionUpdateData(
    val kind: String,
    val text: String? = null,
    val toolCallId: String? = null,
    val title: String? = null,
    val toolKind: String? = null,
    val status: String? = null,
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
    val content: JsonElement? = null,
    val locations: JsonElement? = null,
    val planEntries: List<PlanEntry>? = null,
    val configOptions: List<ConfigOption>? = null,
    val currentModeId: String? = null,
    val used: Long? = null,
    val contextSize: Long? = null,
)

data class PlanEntry(val content: String, val status: String, val priority: String?)

data class ConfigOptionValue(val value: String, val name: String, val group: String? = null)

/** A `select` or `boolean` session config option (model/mode/etc). */
data class ConfigOption(
    val id: String,
    val name: String,
    val type: String,
    /** Optional semantic category (e.g. "model"). */
    val category: String?,
    /** Selected value id (select) or "true"/"false" (boolean). */
    val currentValue: String,
    val options: List<ConfigOptionValue>,
) {
    companion object {
        fun parseAll(arr: JsonElement?): List<ConfigOption> {
            if (arr == null || !arr.isJsonArray) return emptyList()
            val out = ArrayList<ConfigOption>()
            for (el in arr.asJsonArray) {
                val o = el as? JsonObject ?: continue
                val id = o.str("id") ?: continue
                val type = o.str("type") ?: "select"
                val current: JsonElement? = o.get("currentValue")
                val values = ArrayList<ConfigOptionValue>()
                val opts = o.get("options")
                if (opts != null && opts.isJsonArray) {
                    for (opt in opts.asJsonArray) {
                        val oo = opt as? JsonObject ?: continue
                        // Grouped form: { group, name, options: [...] } — keep the group
                        // (provider) name for sectioned rendering.
                        val inner = oo.get("options")
                        if (inner != null && inner.isJsonArray) {
                            val group = oo.str("name")
                            for (io in inner.asJsonArray) {
                                val v = (io as? JsonObject) ?: continue
                                val value = v.str("value") ?: continue
                                values.add(ConfigOptionValue(value, v.str("name") ?: value, group))
                            }
                        } else {
                            val value = oo.str("value") ?: continue
                            values.add(ConfigOptionValue(value, oo.str("name") ?: value))
                        }
                    }
                }
                // Keep the current value VERBATIM: dsh's model option uses JSON-array
                // strings ('["provider","model"]') as both value and currentValue, and
                // the select UI matches by exact equality. (An earlier "first element"
                // unwrap broke model switching.)
                val currentValue = when {
                    current == null || current.isJsonNull -> ""
                    current.isJsonPrimitive && current.asJsonPrimitive.isBoolean -> current.asBoolean.toString()
                    current.isJsonPrimitive -> current.asString
                    else -> current.toString()
                }
                out.add(ConfigOption(id, o.str("name") ?: id, type, o.str("category"), currentValue, values))
            }
            return out
        }
    }
}

/** Parse the `update` object of a `session/update` notification. */
fun parseSessionUpdate(update: JsonObject): SessionUpdateData {
    val kind = update.str("sessionUpdate") ?: return SessionUpdateData("unknown")
    fun contentText(): String? {
        val c = update.get("content") as? JsonObject ?: return null
        return if (c.str("type") == "text") c.str("text") else null
    }
    return when (kind) {
        "agent_message_chunk", "agent_thought_chunk", "user_message_chunk" ->
            SessionUpdateData(kind, text = contentText())
        "tool_call", "tool_call_update" -> SessionUpdateData(
            kind,
            toolCallId = update.str("toolCallId"),
            title = update.str("title"),
            toolKind = update.str("kind"),
            status = update.str("status"),
            rawInput = update.get("rawInput"),
            rawOutput = update.get("rawOutput"),
            content = update.get("content"),
            locations = update.get("locations"),
        )
        "plan" -> SessionUpdateData(kind, planEntries = parsePlan(update.get("entries")))
        "config_option_update", "config_options_update" ->
            SessionUpdateData("config_option_update", configOptions = ConfigOption.parseAll(update.get("configOptions")))
        "current_mode_update" -> SessionUpdateData(kind, currentModeId = update.str("currentModeId"))
        "usage_update" -> SessionUpdateData(kind, used = update.num("used"), contextSize = update.num("size"))
        else -> SessionUpdateData(kind)
    }
}

private fun parsePlan(entries: JsonElement?): List<PlanEntry> {
    if (entries == null || !entries.isJsonArray) return emptyList()
    return entries.asJsonArray.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        PlanEntry(o.str("content") ?: "", o.str("status") ?: "pending", o.str("priority"))
    }
}

/* --------------------------- permissions --------------------------- */

data class PermissionOptionData(val optionId: String, val name: String, val kind: String)

data class PermissionRequestData(
    val sessionId: String,
    val toolCallId: String?,
    val title: String,
    val kind: String?,
    val rawInput: JsonElement?,
    val options: List<PermissionOptionData>,
) {
    companion object {
        fun parse(params: JsonObject): PermissionRequestData {
            val tc = params.get("toolCall") as? JsonObject
            val options = params.getAsJsonArray("options")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("optionId") ?: return@mapNotNull null
                PermissionOptionData(id, o.str("name") ?: id, o.str("kind") ?: "")
            } ?: emptyList()
            return PermissionRequestData(
                sessionId = params.str("sessionId") ?: "",
                toolCallId = tc?.str("toolCallId"),
                title = tc?.str("title") ?: "Permission request",
                kind = tc?.str("kind"),
                rawInput = tc?.get("rawInput"),
                options = options,
            )
        }
    }
}

/* --------------------------- misc responses -------------------------- */

data class SessionInfoData(val sessionId: String, val cwd: String?, val title: String?, val updatedAt: String?) {
    companion object {
        fun parse(o: JsonObject) = SessionInfoData(o.str("sessionId") ?: "", o.str("cwd"), o.str("title"), o.str("updatedAt"))
    }
}

data class AgentIdentity(val name: String?, val version: String?)

data class NewSessionData(
    val sessionId: String,
    val configOptions: List<ConfigOption>,
    val currentModeId: String?,
)

/* ------------------------- json small helpers ------------------------ */

internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.num(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

internal fun JsonObject.el(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

/** Human-readable detail out of an ACP/JSON-RPC error object. */
fun acpErrorText(message: String?, data: JsonElement?): String {
    if (data is JsonObject) {
        val details = data.str("details")
        if (!details.isNullOrBlank() && (message.isNullOrBlank() || message == "Internal error")) return details
    }
    return message ?: "Unknown error"
}

/* --------------------- model/effort helpers --------------------- */

fun findModelOption(options: List<ConfigOption>): ConfigOption? =
    options.find { it.category == "model" || it.id == "model" }

fun findEffortOption(options: List<ConfigOption>): ConfigOption? =
    options.find { it.id == "reasoning_effort" }
        ?: options.firstOrNull { it.name.contains("reasoning", true) || it.name.contains("effort", true) }

/** dsh's ACP server uses '' for the "Provider default" reasoning-effort row. */
const val PROVIDER_DEFAULT_EFFORT_VALUE = ""

/** Effort ranks for the "highest" fallback; unknown ids fall back to list order. */
private val EFFORT_RANK = mapOf("off" to 0, "none" to 0, "minimal" to 1, "low" to 1, "medium" to 2, "high" to 3, "xhigh" to 4, "max" to 5)

/**
 * Pick the effort to pin: the model's configured defaultEffort when present,
 * otherwise the highest declared level. Null when no choice exists.
 */
fun chooseEffort(options: List<ConfigOptionValue>, configuredDefault: String?): String? {
    val opts = options.filter { it.value != PROVIDER_DEFAULT_EFFORT_VALUE }
    if (opts.isEmpty()) return null
    if (configuredDefault != null && opts.any { it.value == configuredDefault }) return configuredDefault
    return opts.reduce { a, b ->
        val ra = EFFORT_RANK[a.value] ?: opts.indexOf(a)
        val rb = EFFORT_RANK[b.value] ?: opts.indexOf(b)
        if (rb > ra) b else a
    }.value
}

/** Extract the model id from an ACP model config value ('["provider","model"]'). */
fun modelIdOf(configValue: String): String? = runCatching {
    val arr = com.google.gson.JsonParser.parseString(configValue).asJsonArray
    if (arr.size() >= 2 && arr[1].isJsonPrimitive) arr[1].asString else null
}.getOrNull()

/**
 * Collect model id → defaultEffort from a parsed dsh config document (any YAML
 * shape: global settings.yaml or per-profile cordis.patch.yml patch lists).
 * Ported from dsh-lite-vscode shared/model.ts collectEffortDefaults.
 */
fun collectEffortDefaults(doc: Any?, into: MutableMap<String, String> = HashMap()): MutableMap<String, String> {
    when (doc) {
        is List<*> -> doc.forEach { collectEffortDefaults(it, into) }
        is Map<*, *> -> {
            val providers = doc["providers"]
            if (providers is Map<*, *>) {
                for (p in providers.values) {
                    val models = (p as? Map<*, *>)?.get("models") as? List<*> ?: continue
                    for (m in models) {
                        val mm = m as? Map<*, *> ?: continue
                        val id = mm["id"] as? String
                        val effort = mm["defaultEffort"] as? String
                        if (id != null && effort != null) into[id] = effort
                    }
                }
            }
            for ((k, v) in doc) if (k != "providers") collectEffortDefaults(v, into)
        }
    }
    return into
}
