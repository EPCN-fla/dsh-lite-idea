/**
 * Launcher abstraction: how to spawn `dsh --profile <p>` on the target side,
 * and how to translate file paths between the IDE side and the dsh side.
 *
 * Supported topologies:
 *   1. IDE on Windows + dsh in WSL   -> WslLauncher   (spawn via wsl.exe)
 *   2. IDE on Linux/macOS + dsh same -> LocalLauncher (spawn directly)
 */
package com.dshlite.idea.launcher

import com.dshlite.idea.settings.DshSettingsState
import com.intellij.openapi.util.SystemInfoRt
import java.io.File
import java.util.concurrent.TimeUnit

data class LaunchSpec(
    val command: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
)

interface Launcher {
    /** Human-readable topology label for diagnostics, e.g. "windows-host -> wsl". */
    val label: String

    /** Build the spawn spec for the ACP server, given the IDE-side workspace path. */
    fun buildLaunchSpec(ideWorkspacePath: String): LaunchSpec

    /** Diagnostic probe spec (used by the Doctor action). */
    fun buildDoctorSpec(): LaunchSpec

    /** Run a POSIX shell script on the target side; null on native-Windows targets. */
    fun buildShellSpec(script: String): LaunchSpec?

    /** IDE-side absolute path -> dsh-side absolute path. */
    fun toDshPath(p: String): String

    /** dsh-side absolute path -> IDE-side absolute path. */
    fun fromDshPath(p: String): String

    /** IDE-side file path -> a correctly encoded ACP resource_link URI. */
    fun toDshResourceUri(p: String): String
}

/** Target-side shell bootstrap: make version-manager-managed Node.js visible to non-interactive shells. */
object ShellEnv {
    fun posixBootstrap(): String = listOf(
        "export NVM_DIR=\"\${NVM_DIR:-\$HOME/.nvm}\"",
        "[ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\" >/dev/null 2>&1",
        "[ -d \"\$HOME/.volta/bin\" ] && export PATH=\"\$HOME/.volta/bin:\$PATH\"",
        "[ -d \"\$HOME/.local/share/mise/shims\" ] && export PATH=\"\$HOME/.local/share/mise/shims:\$PATH\"",
        "[ -d \"\$HOME/.asdf/shims\" ] && export PATH=\"\$HOME/.asdf/shims:\$PATH\"",
        "true",
    ).joinToString("; ")

    /**
     * Short-lived wsl.exe invocations intermittently lose trailing output on some
     * machines (the pipe closes before the last write flushes). A tiny settle
     * delay before exit works around it; harmless on local POSIX shells.
     */
    const val SHORT_CMD_SETTLE = "; sleep 0.2"

    /** Labels and probes on separate lines: no $( ) command substitution, which
     *  is the only construct that broke on the user's wsl.exe invocation path. */
    fun posixDoctor(): String = listOf(
        posixBootstrap(),
        "echo 'node:'; command -v node || echo MISSING; node --version 2>&1 | head -1",
        "echo 'npx:'; command -v npx || echo MISSING",
        "echo 'dsh:'; command -v dsh || echo MISSING",
        "echo 'pwd:'; pwd",
    ).joinToString("; ") + SHORT_CMD_SETTLE

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

internal fun posixFileUri(path: String): String =
    java.net.URI("file", "", path, null, null).toASCIIString()

internal data class WslPathMapping(val path: String, val distro: String?)

/** Map Windows drive and WSL UNC paths without starting wsl.exe on the EDT. */
internal fun mapWslPath(path: String): WslPathMapping {
    val normalized = path.replace('\\', '/')
    val drive = Regex("^([A-Za-z]):/(.*)$").matchEntire(normalized)
    if (drive != null) {
        return WslPathMapping("/mnt/${drive.groupValues[1].lowercase()}/${drive.groupValues[2]}", null)
    }
    // The UNC carries both the distro and its root path. Keep the distro for
    // process launch instead of silently opening the same path in another WSL.
    val unc = Regex("^//(?:wsl\\.localhost|wsl\\$)/([^/]+)(?:/(.*))?$", RegexOption.IGNORE_CASE)
        .matchEntire(normalized)
    return if (unc != null) {
        WslPathMapping("/" + unc.groupValues[2], unc.groupValues[1])
    } else WslPathMapping(path, null)
}

internal fun toWslPath(path: String): String = mapWslPath(path).path

/** Case 2: IDE on Windows, dsh inside WSL. stdio pipes forwarded through wsl.exe. */
class WslLauncher(private val settings: DshSettingsState) : Launcher {
    override val label = "windows-host -> wsl"

    private fun configuredDistro(): String? = settings.wslDistro.trim().takeIf { it.isNotEmpty() }

    private fun wslArgs(innerBash: String, distro: String? = configuredDistro()): List<String> = buildList {
        add("wsl.exe")
        if (distro != null) { add("-d"); add(distro) }
        add("-e"); add("bash"); add("-lc"); add(innerBash)
    }

    override fun buildLaunchSpec(ideWorkspacePath: String): LaunchSpec {
        val mapping = mapWslPath(ideWorkspacePath)
        val dshWorkspaceCwd = mapping.path
        val configured = configuredDistro()
        val distro = mapping.distro?.also { uncDistro ->
            require(configured == null || configured.equals(uncDistro, ignoreCase = true)) {
                "WSL distro mismatch: the project is in '$uncDistro' but settings select '$configured'."
            }
        } ?: configured
        val exports = settings.envMap().entries.joinToString(" ") { (k, v) ->
            "export $k=${ShellEnv.shellQuote(v)};"
        }
        val inner = buildString {
            append(ShellEnv.posixBootstrap()).append("; ")
            if (exports.isNotEmpty()) append(exports).append(' ')
            append("cd ").append(ShellEnv.shellQuote(dshWorkspaceCwd))
            append(" && exec ").append(settings.command)
            append(" --profile ").append(ShellEnv.shellQuote(settings.profile))
        }
        return LaunchSpec(wslArgs(inner, distro))
    }

    override fun buildDoctorSpec(): LaunchSpec = LaunchSpec(wslArgs(ShellEnv.posixDoctor()))

    override fun buildShellSpec(script: String): LaunchSpec {
        val exports = settings.envMap().entries.joinToString(" ") { (key, value) -> "export $key=${ShellEnv.shellQuote(value)};" }
        return LaunchSpec(wslArgs("$exports $script"))
    }

    private val wslMount = Regex("""^/mnt/([a-z])/(.*)$""")

    override fun toDshPath(p: String): String = toWslPath(p)

    override fun toDshResourceUri(p: String): String = posixFileUri(toDshPath(p))

    override fun fromDshPath(p: String): String {
        val m = wslMount.matchEntire(p)
        if (m != null) return "${m.groupValues[1].uppercase()}:\\${m.groupValues[2].replace('/', '\\')}"
        // WSL-native path (e.g. /tmp exports): map to the \\wsl.localhost UNC so
        // Windows-side reveal/copy works. wslpath -w does exactly that.
        return wslPath(p, "-w") ?: p
    }

    private fun wslPath(p: String, flag: String): String? = runCatching {
        val args = buildList {
            add("wsl.exe")
            if (settings.wslDistro.isNotBlank()) { add("-d"); add(settings.wslDistro) }
            add("-e"); add("wslpath"); add(flag); add(p)
        }
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        // waitFor BEFORE reading: a hung child must not block past the timeout.
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching null
        }
        proc.inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()
}

/** Same-side launch: IDE and dsh on the same machine (Linux/macOS, or native Windows). */
class LocalLauncher(private val settings: DshSettingsState) : Launcher {
    override val label = "local (${SystemInfoRt.OS_NAME})"

    override fun buildLaunchSpec(ideWorkspacePath: String): LaunchSpec {
        val dshWorkspaceCwd = toDshPath(ideWorkspacePath)
        if (SystemInfoRt.isWindows) {
            // Native Windows dsh: no posix bootstrap; cmd.exe resolves npx .cmd shims.
            // cwd goes through the ProcessBuilder, not a cd, to keep quoting simple.
            return LaunchSpec(
                command = listOf("cmd.exe", "/c", "${settings.command} --profile \"${settings.profile}\""),
                cwd = dshWorkspaceCwd,
                env = settings.envMap(),
            )
        }
        val inner = buildString {
            append(ShellEnv.posixBootstrap()).append("; ")
            val exports = settings.envMap().entries.joinToString(" ") { (k, v) -> "export $k=${ShellEnv.shellQuote(v)};" }
            if (exports.isNotEmpty()) append(exports).append(' ')
            append("cd ").append(ShellEnv.shellQuote(dshWorkspaceCwd))
            append(" && exec ").append(settings.command)
            append(" --profile ").append(ShellEnv.shellQuote(settings.profile))
        }
        return LaunchSpec(listOf("/bin/sh", "-lc", inner))
    }

    override fun buildDoctorSpec(): LaunchSpec {
        if (SystemInfoRt.isWindows) {
            return LaunchSpec(listOf("cmd.exe", "/c", "where node & node --version & where dsh & cd"))
        }
        return LaunchSpec(listOf("/bin/sh", "-lc", ShellEnv.posixDoctor()))
    }

    override fun buildShellSpec(script: String): LaunchSpec? =
        if (SystemInfoRt.isWindows) null // native Windows target: no POSIX shell
        else LaunchSpec(listOf("/bin/sh", "-lc", script), env = settings.envMap())

    override fun toDshPath(p: String): String = File(p).absolutePath
    override fun fromDshPath(p: String): String = p
    override fun toDshResourceUri(p: String): String {
        val path = toDshPath(p)
        return if (SystemInfoRt.isWindows) File(path).toURI().toASCIIString() else posixFileUri(path)
    }
}

/**
 * Topology matrix (mirrors dsh-lite-vscode):
 *   Windows IDE + auto/wsl     -> WslLauncher   (windows -> wsl, the primary topology)
 *   Windows IDE + windows      -> LocalLauncher (windows -> windows, native cmd.exe)
 *   Linux/macOS IDE (any)      -> LocalLauncher (same-side; covers the WSL<->WSL case
 *                                  when the IDE backend itself runs inside WSL)
 * "local" is accepted as a legacy alias for the same-side/native launch.
 */
fun createLauncher(settings: DshSettingsState): Launcher {
    if (!SystemInfoRt.isWindows) return LocalLauncher(settings)
    return when (settings.runtime) {
        "windows", "local" -> LocalLauncher(settings)
        else -> WslLauncher(settings)
    }
}
