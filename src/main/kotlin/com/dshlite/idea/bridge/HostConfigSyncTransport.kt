package com.dshlite.idea.bridge

import com.dshlite.idea.launcher.ShellEnv
import com.dshlite.idea.settings.DshSettingsState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

internal class ProfileImportException(
    val code: String,
    val packages: List<String> = emptyList(),
) : Exception(code)

internal interface ConfigSyncTransport {
    fun probeSource(source: String): CompletableFuture<Boolean>
    fun preview(source: String, target: String, options: ConfigSyncOptions): CompletableFuture<ConfigSyncSummary>
    fun import(
        source: String,
        target: String,
        options: ConfigSyncOptions,
        revision: String,
    ): CompletableFuture<ConfigSyncSummary>
}

/** Secret-free host transport; profile bytes never cross into the IDE process. */
internal class HostConfigSyncTransport(
    private val project: Project,
    private val hostCode: () -> String = {
        HostConfigSyncTransport::class.java.getResourceAsStream("/bridge/profile-import-host.mjs")!!
            .readBytes().toString(StandardCharsets.UTF_8)
    },
) : ConfigSyncTransport {
    private fun home(): String? = DshSettingsState.getInstance().envMap()["DSH_HOME"]?.takeIf { it.isNotBlank() }

    override fun probeSource(source: String): CompletableFuture<Boolean> =
        BridgeInstall.runScript(buildProfileProbeScript(source, home()), 15_000, ideWorkspacePath = project.basePath)
            .thenApply { it.first.trim() == "exists" }

    override fun preview(source: String, target: String, options: ConfigSyncOptions): CompletableFuture<ConfigSyncSummary> =
        run(requestJson("preview", source, target, options), 30_000)

    override fun import(
        source: String,
        target: String,
        options: ConfigSyncOptions,
        revision: String,
    ): CompletableFuture<ConfigSyncSummary> =
        run(requestJson("import", source, target, options, revision), 60_000)

    private fun run(request: JsonObject, timeoutMs: Long): CompletableFuture<ConfigSyncSummary> {
        val command = DshSettingsState.getInstance().command
        val script = buildProfileImportScript(hostCode(), command, request, home())
        return BridgeInstall.runScript(script, timeoutMs, ideWorkspacePath = project.basePath)
            .thenApply { parseProfileImportHostResult(it.first) }
    }

    private companion object {
        fun requestJson(
            operation: String,
            source: String,
            target: String,
            options: ConfigSyncOptions,
            revision: String? = null,
        ) = JsonObject().apply {
            addProperty("operation", operation)
            addProperty("source", source)
            addProperty("target", target)
            addProperty("models", options.models)
            addProperty("presets", options.presets)
            addProperty("overwrite", options.overwrite)
            revision?.let { addProperty("revision", it) }
        }
    }
}

private const val UNAVAILABLE = "{\"ok\":false,\"code\":\"config/unavailable\"}"
private const val NODE_UNAVAILABLE = "{\"ok\":false,\"code\":\"config/node-unavailable\"}"

private fun homePrefix(home: String?): String = home?.let { "export DSH_HOME=${ShellEnv.shellQuote(it)}\n" }.orEmpty()

internal fun buildProfileProbeScript(source: String, dshHome: String? = null): String =
    homePrefix(dshHome) + """DSH_HOME="${'$'}{DSH_HOME:-${'$'}HOME/.dsh}"
PROFILE_NAME=${ShellEnv.shellQuote(source)}
PROFILE="${'$'}DSH_HOME/profiles/${'$'}PROFILE_NAME"
if [ -f "${'$'}PROFILE/package.json" ]; then printf 'exists\n'; fi
"""

private fun heredocMarker(code: String): String {
    var marker = "DSH_PROFILE_IMPORT_HOST"
    val lines = code.lines()
    while (lines.any { it == marker || it == "$marker\r" }) marker += "_X"
    return marker
}

/** Port of profileImportScript.ts: code/request are data, never shell syntax. */
internal fun buildProfileImportScript(
    hostCode: String,
    dshCommand: String,
    request: JsonObject,
    dshHome: String? = null,
): String {
    val marker = heredocMarker(hostCode)
    val hostBody = if (hostCode.endsWith('\n')) hostCode else hostCode + '\n'
    val requestJson = com.google.gson.Gson().toJson(request)
    return """# DSH profile import host launcher
${homePrefix(dshHome)}${ShellEnv.posixBootstrap()}
if ! command -v node >/dev/null 2>&1; then
  printf '%s\n' '$NODE_UNAVAILABLE'
  exit 0
fi
DSH_HOME="${'$'}{DSH_HOME:-${'$'}HOME/.dsh}"
export DSH_HOME
DSH_IMPORT_REQUEST=${ShellEnv.shellQuote(requestJson)}
export DSH_IMPORT_REQUEST
DSH_WORK="$(mktemp -d "${'$'}{TMPDIR:-/tmp}/dsh-profile-import.XXXXXXXXXX" 2>/dev/null)" || DSH_WORK=
if [ -z "${'$'}DSH_WORK" ]; then
  printf '%s\n' '$UNAVAILABLE'
  exit 0
fi
trap 'rm -rf -- "${'$'}DSH_WORK"' EXIT
trap 'exit 0' HUP INT TERM

cat > "${'$'}DSH_WORK/host.mjs" <<'$marker'
$hostBody$marker
if [ "${'$'}?" -ne 0 ]; then
  printf '%s\n' '$UNAVAILABLE'
  exit 0
fi

DSH_IMPORT_ANCHOR="${'$'}DSH_HOME/profiles/node_modules/@deepseek-ai/dsh/package.json"
if [ ! -f "${'$'}DSH_IMPORT_ANCHOR" ]; then
  DSH_IMPORT_ANCHOR=
  cat > "${'$'}DSH_WORK/probe.cjs" <<'DSH_PROFILE_IMPORT_PROBE'
const fs = require('node:fs')
const path = require('node:path')
try {
  const output = process.env.DSH_IMPORT_ANCHOR_FILE
  if (output && process.argv[1]) {
    let dir = path.dirname(path.resolve(process.argv[1]))
    for (;;) {
      const candidate = path.join(dir, 'package.json')
      try {
        const manifest = JSON.parse(fs.readFileSync(candidate, 'utf8'))
        if (manifest && manifest.name === '@deepseek-ai/dsh') {
          try { fs.writeFileSync(output, candidate + '\n', { flag: 'wx' }) } catch (error) {
            if (!error || error.code !== 'EEXIST') throw error
          }
          break
        }
      } catch {}
      const parent = path.dirname(dir)
      if (parent === dir) break
      dir = parent
    }
  }
} catch {}
DSH_PROFILE_IMPORT_PROBE
  if [ "${'$'}?" -ne 0 ]; then
    printf '%s\n' '$UNAVAILABLE'
    exit 0
  fi
  DSH_PROBE_NODE_OPTIONS="${'$'}{NODE_OPTIONS:+${'$'}{NODE_OPTIONS} }--require \"${'$'}DSH_WORK/probe.cjs\""
  NODE_OPTIONS="${'$'}DSH_PROBE_NODE_OPTIONS" DSH_IMPORT_ANCHOR_FILE="${'$'}DSH_WORK/anchor" $dshCommand --version >/dev/null 2>/dev/null || :
  if [ -f "${'$'}DSH_WORK/anchor" ]; then
    IFS= read -r DSH_IMPORT_ANCHOR < "${'$'}DSH_WORK/anchor" || :
  fi
fi

if [ -z "${'$'}DSH_IMPORT_ANCHOR" ] || [ ! -f "${'$'}DSH_IMPORT_ANCHOR" ]; then
  printf '%s\n' '$UNAVAILABLE'
  exit 0
fi
export DSH_IMPORT_ANCHOR
DSH_HOST_OUTPUT="$(node "${'$'}DSH_WORK/host.mjs" 2>/dev/null)"
DSH_HOST_STATUS=${'$'}?
if [ "${'$'}DSH_HOST_STATUS" -ne 0 ] || [ -z "${'$'}DSH_HOST_OUTPUT" ]; then
  printf '%s\n' '$UNAVAILABLE'
else
  printf '%s\n' "${'$'}DSH_HOST_OUTPUT"
fi
exit 0
"""
}

/** Parse only the final JSON line; never expose configuration-bearing output. */
internal fun parseProfileImportHostResult(stdout: String): ConfigSyncSummary {
    val line = stdout.trim().lineSequence().lastOrNull().orEmpty()
    try {
        val value = JsonParser.parseString(line).asJsonObject
        if (value.get("ok")?.asBoolean == true) {
            return ConfigSyncSummary.from(value.getAsJsonObject("summary"))
        }
        val packages = value.getAsJsonArray("packages")?.mapNotNull {
            it.takeIf { el -> el.isJsonPrimitive && el.asJsonPrimitive.isString }?.asString
        } ?: emptyList()
        throw ProfileImportException(value.get("code")?.asString ?: "config/unavailable", packages)
    } catch (e: ProfileImportException) {
        throw e
    } catch (_: Exception) {
        throw ProfileImportException("config/unavailable")
    }
}
