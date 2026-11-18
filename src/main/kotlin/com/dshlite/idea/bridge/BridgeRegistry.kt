/**
 * Online bridge-registry metadata parsing and compatible-version selection.
 * Pure Kotlin/Gson port of dsh-lite-vscode src/bridge/registry.ts and
 * hostVersion.ts; this file deliberately has no IntelliJ or Node dependency.
 */
package com.dshlite.idea.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger

data class DshVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: String? = null,
)

data class BridgeRegistryEntry(
    val version: String,
    /** Explicit host cohort keys. A non-null empty map still disables legacy fallback. */
    val dshCompatibility: Map<String, Any?>? = null,
    /** Value of `devDependencies.@deepseek-ai/dsh-agent` in legacy package metadata. */
    val devDependencyDshAgent: String? = null,
)

class BridgeRegistryError(val code: String, message: String) : RuntimeException(message)

object BridgeRegistry {
    const val REGISTRY_INVALID = "registry/invalid"
    const val REGISTRY_NO_COMPATIBLE = "registry/no-compatible"

    private const val LEGACY_DEPENDENCY = "devDependencies.@deepseek-ai/dsh-agent"
    private val VERSION_IN_TEXT = Regex("""([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([0-9A-Za-z.-]+))?""")
    private val REGISTRY_VERSION = Regex("""^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$""")
    private val NUMERIC_IDENTIFIER = Regex("""^[0-9]+$""")
    private val RC_IDENTIFIER = Regex("""^rc\.[0-9]+$""")

    /** Extract the first semantic version from free text, or return null. */
    fun parseDshVersion(text: String?): DshVersion? {
        val match = text?.let(VERSION_IN_TEXT::find) ?: return null
        return DshVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = match.groupValues[4].ifEmpty { null },
        )
    }

    fun formatDshVersion(version: DshVersion): String = buildString {
        append(version.major)
        append('.')
        append(version.minor)
        append('.')
        append(version.patch)
        version.prerelease?.let {
            append('-')
            append(it)
        }
    }

    /** SemVer precedence, including numeric/string prerelease identifiers. */
    fun compareDshVersions(a: DshVersion, b: DshVersion): Int {
        if (a.major != b.major) return if (a.major < b.major) -1 else 1
        if (a.minor != b.minor) return if (a.minor < b.minor) -1 else 1
        if (a.patch != b.patch) return if (a.patch < b.patch) -1 else 1
        if (a.prerelease == b.prerelease) return 0
        if (a.prerelease == null) return 1
        if (b.prerelease == null) return -1

        val left = a.prerelease.split('.')
        val right = b.prerelease.split('.')
        for (index in 0 until maxOf(left.size, right.size)) {
            if (index >= left.size) return -1
            if (index >= right.size) return 1
            if (left[index] == right[index]) continue
            val leftNumeric = NUMERIC_IDENTIFIER.matches(left[index])
            val rightNumeric = NUMERIC_IDENTIFIER.matches(right[index])
            if (leftNumeric && rightNumeric) {
                return BigInteger(left[index]).compareTo(BigInteger(right[index]))
            }
            if (leftNumeric != rightNumeric) return if (leftNumeric) -1 else 1
            return if (left[index] < right[index]) -1 else 1
        }
        return 0
    }

    /** Recognized host cohorts from the historical extension/bridge test matrix. */
    fun isTestedHostVersion(version: DshVersion): Boolean {
        if (version.major != 0L) return false
        if (version.minor == 1L) return version.patch == 5L || version.patch == 7L
        if (version.minor != 2L) return false
        if (version.patch == 0L) return true
        if (version.patch == 1L) return RC_IDENTIFIER.matches(version.prerelease.orEmpty())
        return version.patch == 2L && version.prerelease in setOf("rc.1", "rc.2", "rc.3")
    }

    /** Parse either one npm registry object or an array, filtering invalid rows. */
    fun parseBridgeRegistryResponse(text: String): List<BridgeRegistryEntry> {
        val value = try {
            JsonReader(StringReader(text)).use { reader ->
                val parsed = readJsonElement(reader)
                check(reader.peek() == JsonToken.END_DOCUMENT)
                parsed
            }
        } catch (_: Exception) {
            throw BridgeRegistryError(REGISTRY_INVALID, "Registry returned malformed JSON")
        }
        val rows = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
        return rows.mapNotNull { row ->
            if (!row.isJsonObject) return@mapNotNull null
            val objectRow = row.asJsonObject
            val versionNode = objectRow.get("version")
            if (versionNode == null || !versionNode.isJsonPrimitive || !versionNode.asJsonPrimitive.isString) {
                return@mapNotNull null
            }
            val version = versionNode.asString
            if (!REGISTRY_VERSION.matches(version)) return@mapNotNull null

            // Presence, not truthiness, is significant here. Malformed explicit
            // metadata therefore fails closed instead of reopening legacy ranges.
            val compatibility: Map<String, Any?>? = if (!objectRow.has("dshCompatibility")) {
                null
            } else {
                val node = objectRow.get("dshCompatibility")
                if (node != null && node.isJsonObject) {
                    node.asJsonObject.entrySet().associate { it.key to null }
                } else {
                    emptyMap()
                }
            }
            val dependencyNode = objectRow.get(LEGACY_DEPENDENCY)
            val dependency = dependencyNode
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            BridgeRegistryEntry(version, compatibility, dependency)
        }
    }

    /** Gson 2.10's JsonParser forcibly enables lenient mode, so read the tree directly. */
    private fun readJsonElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val result = JsonObject()
            reader.beginObject()
            while (reader.hasNext()) result.add(reader.nextName(), readJsonElement(reader))
            reader.endObject()
            result
        }
        JsonToken.BEGIN_ARRAY -> {
            val result = JsonArray()
            reader.beginArray()
            while (reader.hasNext()) result.add(readJsonElement(reader))
            reader.endArray()
            result
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> {
            reader.nextNull()
            JsonNull.INSTANCE
        }
        else -> throw IllegalArgumentException("Unexpected JSON token")
    }

    /** Convenience entry point for BridgeInstall: parse host and registry, then select. */
    fun selectLatestCompatibleBridge(hostVersion: String?, registryJson: String): String {
        val host = parseDshVersion(hostVersion)
            ?: throw BridgeRegistryError(REGISTRY_INVALID, "Invalid DSH host version")
        return selectLatestCompatibleBridge(host, parseBridgeRegistryResponse(registryJson))
    }

    /** Highest compatible SemVer; stable releases win over all prereleases. */
    fun selectLatestCompatibleBridge(host: DshVersion, entries: List<BridgeRegistryEntry>): String {
        val compatible = entries.filter { compatibleWith(host, it) }
        // Keep the reference's optional-chaining behavior: a manually supplied
        // invalid version reaches validation below instead of being silently used.
        val stable = compatible.filter { parseDshVersion(it.version)?.prerelease == null }
        val candidates = if (stable.isNotEmpty()) stable else compatible
        val sorted = candidates.sortedWith { a, b ->
            val aVersion = parseDshVersion(a.version) ?: throw invalidBridgeVersion()
            val bVersion = parseDshVersion(b.version) ?: throw invalidBridgeVersion()
            compareDshVersions(aVersion, bVersion)
        }
        val selected = sorted.lastOrNull() ?: throw BridgeRegistryError(
            REGISTRY_NO_COMPATIBLE,
            "No published bridge declares compatibility with DSH ${formatDshVersion(host)}",
        )
        val version = parseDshVersion(selected.version) ?: throw invalidBridgeVersion()
        return "dsh-vscode-bridge@${formatDshVersion(version)}"
    }

    private fun invalidBridgeVersion() =
        BridgeRegistryError(REGISTRY_INVALID, "Registry returned an invalid bridge version")

    /** Stable tested hosts may use their matching x.y.z-rc.N metadata cohort. */
    private fun sameTestedReleaseCohort(host: DshVersion, candidate: DshVersion): Boolean =
        host.prerelease == null &&
            candidate.major == host.major && candidate.minor == host.minor && candidate.patch == host.patch &&
            RC_IDENTIFIER.matches(candidate.prerelease.orEmpty()) &&
            isTestedHostVersion(host)

    /** Explicit cohorts take priority; legacy metadata uses node-semver prerelease rules. */
    private fun compatibleWith(host: DshVersion, entry: BridgeRegistryEntry): Boolean {
        val hostName = formatDshVersion(host)
        val compatibility = entry.dshCompatibility
        if (compatibility != null) {
            if (compatibility.containsKey(hostName)) return true
            return compatibility.keys.any { key ->
                val candidate = parseDshVersion(key)
                candidate != null && sameTestedReleaseCohort(host, candidate)
            }
        }

        val spec = entry.devDependencyDshAgent?.trim()
        if (spec.isNullOrEmpty()) return false
        if (spec == hostName) return true
        val specVersion = parseDshVersion(spec)
        if (specVersion != null && sameTestedReleaseCohort(host, specVersion)) return true
        return satisfiesLegacyRange(host, spec)
    }

    // Minimal node-semver compatible range evaluator for registry metadata.
    // Besides exact/caret, it supports tilde, comparators, x-ranges, hyphen
    // ranges, and ||. Unsupported or malformed specs simply do not match.
    private enum class Operator { LT, LTE, GT, GTE, EQ }
    private data class Comparator(val operator: Operator, val version: DshVersion)
    private data class PartialVersion(
        val major: Long?,
        val minor: Long?,
        val patch: Long?,
        val prerelease: String?,
    ) {
        fun full(): DshVersion? = if (major != null && minor != null && patch != null) {
            DshVersion(major, minor, patch, prerelease)
        } else {
            null
        }

        fun zeroFilled(): DshVersion? = major?.let { DshVersion(it, minor ?: 0, patch ?: 0, prerelease) }
    }

    private val RANGE_VERSION = Regex(
        """^v?([0-9]+|[xX*])(?:\.([0-9]+|[xX*]))?(?:\.([0-9]+|[xX*]))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""",
    )
    private val RANGE_TOKEN = Regex("""^(<=|>=|<|>|=|\^|~>|~)?(.+)$""")
    private val HYPHEN_RANGE = Regex("""^(\S+)\s+-\s+(\S+)$""")
    private val OPERATOR_ONLY = setOf("<", "<=", ">", ">=", "=", "^", "~", "~>")

    private fun satisfiesLegacyRange(host: DshVersion, spec: String): Boolean {
        val alternatives = parseRange(spec) ?: return false
        return alternatives.any { comparators ->
            comparators.all { matches(it, host) } &&
                (host.prerelease == null || comparators.any {
                    it.version.prerelease != null &&
                        it.version.major == host.major && it.version.minor == host.minor && it.version.patch == host.patch
                })
        }
    }

    private fun matches(comparator: Comparator, host: DshVersion): Boolean {
        val comparison = compareDshVersions(host, comparator.version)
        return when (comparator.operator) {
            Operator.LT -> comparison < 0
            Operator.LTE -> comparison <= 0
            Operator.GT -> comparison > 0
            Operator.GTE -> comparison >= 0
            Operator.EQ -> comparison == 0
        }
    }

    private fun parseRange(spec: String): List<List<Comparator>>? {
        if (spec.isBlank()) return null
        val result = mutableListOf<List<Comparator>>()
        for (alternative in spec.split("||")) {
            result += parseComparatorSet(alternative.trim()) ?: return null
        }
        return result
    }

    private fun parseComparatorSet(text: String): List<Comparator>? {
        if (text.isEmpty()) return null
        HYPHEN_RANGE.matchEntire(text)?.let { match ->
            val lower = parsePartialVersion(match.groupValues[1]) ?: return null
            val upper = parsePartialVersion(match.groupValues[2]) ?: return null
            return hyphenComparators(lower, upper)
        }

        val rawTokens = text.split(Regex("""\s+"""))
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < rawTokens.size) {
            val token = rawTokens[index]
            if (token in OPERATOR_ONLY) {
                if (++index >= rawTokens.size) return null
                tokens += token + rawTokens[index]
            } else {
                tokens += token
            }
            index++
        }
        val comparators = mutableListOf<Comparator>()
        for (token in tokens) {
            comparators += parseRangeToken(token) ?: return null
        }
        return comparators
    }

    private fun parseRangeToken(token: String): List<Comparator>? {
        val match = RANGE_TOKEN.matchEntire(token) ?: return null
        val operator = match.groupValues[1].ifEmpty { "=" }
        val version = parsePartialVersion(match.groupValues[2]) ?: return null
        return when (operator) {
            "^" -> caretComparators(version)
            "~", "~>" -> tildeComparators(version)
            "=" -> exactComparators(version)
            else -> comparisonComparators(Operator.valueOf(operatorName(operator)), version)
        }
    }

    private fun operatorName(operator: String): String = when (operator) {
        "<" -> "LT"
        "<=" -> "LTE"
        ">" -> "GT"
        ">=" -> "GTE"
        else -> "EQ"
    }

    private fun parsePartialVersion(text: String): PartialVersion? {
        val match = RANGE_VERSION.matchEntire(text) ?: return null
        val raw = listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        for (component in raw) {
            if (component.isEmpty() || component in setOf("x", "X", "*")) continue
            if ((component.length > 1 && component.startsWith('0')) || component.toLongOrNull() == null) {
                return null
            }
        }
        val values = raw.map { component ->
            component.takeIf { it.isNotEmpty() && it !in setOf("x", "X", "*") }?.toLongOrNull()
        }
        // A wildcard or omitted component makes every following component invalid.
        for (index in 1 until values.size) {
            if (values[index] != null && values[index - 1] == null) return null
        }
        val prerelease = match.groupValues[4].ifEmpty { null }
        if (prerelease != null && values.any { it == null }) return null
        if (prerelease != null && prerelease.split('.').any {
                it.isEmpty() || (NUMERIC_IDENTIFIER.matches(it) && it.length > 1 && it.startsWith('0'))
            }
        ) {
            return null
        }
        return PartialVersion(values[0], values[1], values[2], prerelease)
    }

    private fun exactComparators(version: PartialVersion): List<Comparator>? {
        version.full()?.let { return listOf(Comparator(Operator.EQ, it)) }
        val lower = version.zeroFilled() ?: return emptyList()
        val upper = when {
            version.minor == null -> nextMajor(version.major!!, prerelease = "0")
            else -> nextMinor(version.major!!, version.minor, prerelease = "0")
        } ?: return null
        return listOf(Comparator(Operator.GTE, lower), Comparator(Operator.LT, upper))
    }

    private fun caretComparators(version: PartialVersion): List<Comparator>? {
        val lower = version.zeroFilled() ?: return emptyList()
        val upper = when {
            version.major!! > 0 -> nextMajor(version.major, prerelease = "0")
            version.minor == null -> nextMajor(version.major, prerelease = "0")
            version.minor > 0 -> nextMinor(version.major, version.minor, prerelease = "0")
            version.patch == null -> nextMinor(version.major, version.minor, prerelease = "0")
            else -> nextPatch(version.major, version.minor, version.patch, prerelease = "0")
        } ?: return null
        return listOf(Comparator(Operator.GTE, lower), Comparator(Operator.LT, upper))
    }

    private fun tildeComparators(version: PartialVersion): List<Comparator>? {
        val lower = version.zeroFilled() ?: return emptyList()
        val upper = if (version.minor == null) {
            nextMajor(version.major!!, prerelease = "0")
        } else {
            nextMinor(version.major!!, version.minor, prerelease = "0")
        } ?: return null
        return listOf(Comparator(Operator.GTE, lower), Comparator(Operator.LT, upper))
    }

    private fun comparisonComparators(operator: Operator, version: PartialVersion): List<Comparator>? {
        version.full()?.let { return listOf(Comparator(operator, it)) }
        if (version.major == null) {
            return if (operator == Operator.GTE || operator == Operator.LTE) emptyList() else null
        }
        val lower = version.zeroFilled() ?: return null
        return when (operator) {
            Operator.GTE -> listOf(Comparator(Operator.GTE, lower))
            Operator.LT -> listOf(Comparator(Operator.LT, lower.copy(prerelease = "0")))
            Operator.GT -> {
                val next = if (version.minor == null) {
                    nextMajor(version.major, prerelease = null)
                } else {
                    nextMinor(version.major, version.minor, prerelease = null)
                } ?: return null
                listOf(Comparator(Operator.GTE, next))
            }
            Operator.LTE -> {
                val next = if (version.minor == null) {
                    nextMajor(version.major, prerelease = "0")
                } else {
                    nextMinor(version.major, version.minor, prerelease = "0")
                } ?: return null
                listOf(Comparator(Operator.LT, next))
            }
            Operator.EQ -> exactComparators(version)
        }
    }

    private fun hyphenComparators(lower: PartialVersion, upper: PartialVersion): List<Comparator>? {
        val result = mutableListOf<Comparator>()
        lower.zeroFilled()?.let { result += Comparator(Operator.GTE, it) }
        upper.full()?.let {
            result += Comparator(Operator.LTE, it)
            return result
        }
        if (upper.major != null) {
            val exclusive = if (upper.minor == null) {
                nextMajor(upper.major, prerelease = "0")
            } else {
                nextMinor(upper.major, upper.minor, prerelease = "0")
            } ?: return null
            result += Comparator(Operator.LT, exclusive)
        }
        return result
    }

    private fun nextMajor(major: Long, prerelease: String?): DshVersion? =
        if (major == Long.MAX_VALUE) null else DshVersion(major + 1, 0, 0, prerelease)

    private fun nextMinor(major: Long, minor: Long, prerelease: String?): DshVersion? =
        if (minor == Long.MAX_VALUE) null else DshVersion(major, minor + 1, 0, prerelease)

    private fun nextPatch(major: Long, minor: Long, patch: Long, prerelease: String?): DshVersion? =
        if (patch == Long.MAX_VALUE) null else DshVersion(major, minor, patch + 1, prerelease)
}
