/**
 * One-shot bridge installer/repairer, ported from dsh-lite-vscode
 * src/bridge/install.ts + installScript.ts. The bash template and the
 * cordis.patch.yml variants are vendored as resources. The patch variants
 * remain byte-identical to the VS Code sources; the install script additionally
 * reapplies the requested package spec so Repair also upgrades an older bridge.
 * Only __COMMAND__/__PKG__ are interpolated at run time.
 *
 * Steps (all idempotent, executed by the script on the target side):
 *   1. create the acp-vscode profile from the acp template if missing
 *   2. ensure @deepseek-ai/dsh-acp-app is in the profile bundles
 *   3. write/repair cordis.patch.yml per the host dsh version
 *   4. install the bridge package into the profile
 */
package com.dshlite.idea.bridge

import com.dshlite.idea.DshBundle
import com.dshlite.idea.settings.DshSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfoRt
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object BridgeInstall {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(BridgeInstall::class.java)

    const val BRIDGE_SPEC_CURRENT = "dsh-vscode-bridge@^0.4.0"
    const val BRIDGE_SPEC_LEGACY = "dsh-vscode-bridge@^0.2.0"

    private fun resource(name: String): String =
        BridgeInstall::class.java.getResourceAsStream("/bridge/$name")!!
            .readBytes().toString(StandardCharsets.UTF_8)

    /** Bridge package spec to pre-fill for a host dsh version string. */
    fun bridgeSpecForHost(version: String?): String {
        val m = version?.let { Regex("""(\d+)\.(\d+)\.(\d+)""").find(it) } ?: return BRIDGE_SPEC_CURRENT
        val major = m.groupValues[1].toInt(); val minor = m.groupValues[2].toInt(); val patch = m.groupValues[3].toInt()
        return if (major == 0 && (minor == 0 || (minor == 1 && patch <= 6))) BRIDGE_SPEC_LEGACY else BRIDGE_SPEC_CURRENT
    }

    fun buildVersionProbeScript(command: String): String =
        "$command --version 2>/dev/null | grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?' | head -n1"

    /** Assemble the full install script for the target side. */
    fun buildInstallScript(command: String, pkg: String): String =
        resource("install-template.sh")
            .replace("__PATCH_CURRENT__", resource("cordis-patch-current.yml"))
            .replace("__PATCH_PRESETS__", resource("cordis-preset-rows.yml"))
            .replace("__PATCH_LEGACY__", resource("cordis-patch-legacy.yml"))
            .replace("__COMMAND__", command)
            .replace("__PKG__", pkg)

    private fun targetShellArgs(): List<String> {
        val s = DshSettingsState.getInstance()
        val wsl = com.dshlite.idea.launcher.usesWsl(s.runtime, SystemInfoRt.isWindows)
        return if (wsl) buildList {
            add("wsl.exe")
            if (s.wslDistro.isNotBlank()) { add("-d"); add(s.wslDistro) }
            add("-e"); add("bash"); add("-s")
        } else listOf("/bin/bash", "-s")
    }

    /** Run a shell script over stdin on the target side (not argv: the preset rows exceed the Windows argv limit). */
    private fun runScript(script: String, timeoutMs: Long): CompletableFuture<Pair<String, String>> {
        val future = CompletableFuture<Pair<String, String>>()
        ApplicationManager.getApplication().executeOnPooledThread {
            var proc: Process? = null
            try {
                proc = ProcessBuilder(targetShellArgs()).start()
                // Drain both streams concurrently: a full stderr pipe would block
                // the child while we are still reading stdout (classic deadlock).
                val process = proc
                val stdoutF = com.dshlite.idea.util.ioAsync("dsh-install-stdout") { process.inputStream.bufferedReader().readText() }
                val stderrF = com.dshlite.idea.util.ioAsync("dsh-install-stderr") { process.errorStream.bufferedReader().readText() }
                val stdin = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
                try {
                    stdin.write(
                        com.dshlite.idea.launcher.ShellEnv.withTargetEnvironment(
                            script,
                            DshSettingsState.getInstance().envMap(),
                        )
                    )
                    stdin.flush()
                } finally {
                    runCatching { stdin.close() } // EPIPE when the child dies before reading
                }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    future.completeExceptionally(Exception("install timed out after ${timeoutMs / 1000}s"))
                    return@executeOnPooledThread
                }
                val stdout = stdoutF.get(10, TimeUnit.SECONDS)
                val stderr = stderrF.get(10, TimeUnit.SECONDS)
                if (process.exitValue() != 0) {
                    future.completeExceptionally(Exception("exit code ${process.exitValue()} — ${stderr.trim().takeLast(300)}"))
                } else {
                    future.complete(stdout to stderr)
                }
            } catch (e: Exception) {
                runCatching { proc?.destroyForcibly() }
                future.completeExceptionally(e)
            }
        }
        return future
    }

    /** Interactive flow: probe host version → confirm package spec → install → point dsh.profile at acp-vscode. */
    fun run(project: Project, bridge: BridgeManager, onInstalled: () -> Unit = {}) {
        val rt = DshSettingsState.getInstance().runtime
        if (SystemInfoRt.isWindows && (rt == "windows" || rt == "local")) {
            Messages.showWarningDialog(
                project,
                DshBundle.message("bridge.install.windowsUnsupported"),
                DshBundle.message("bridge.install.title"),
            )
            return
        }
        val s = DshSettingsState.getInstance()
        // Called from a toolbar action (EDT): the probe runs a target-side
        // process and must not block the UI — move it off-EDT first.
        ApplicationManager.getApplication().executeOnPooledThread {
            val probed = bridge.hostDshVersion ?: runCatching {
                runScript(buildVersionProbeScript(s.command), 30_000).get(35, TimeUnit.SECONDS).first.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val suggested = bridgeSpecForHost(probed)
            promptAndInstall(project, bridge, s, probed, suggested, onInstalled)
        }
    }

    private fun promptAndInstall(
        project: Project,
        bridge: BridgeManager,
        s: DshSettingsState,
        probed: String?,
        suggested: String,
        onInstalled: () -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val pkg = Messages.showInputDialog(
                project,
                DshBundle.message("bridge.install.prompt", probed ?: "(not detected)"),
                DshBundle.message("bridge.install.title"),
                null,
                suggested,
                null,
            ) ?: return@invokeLater
            log.info("[install] starting: package=$pkg dsh=${probed ?: "unknown"}")
            runScript(buildInstallScript(s.command, pkg), 180_000).whenComplete { out, error ->
                ApplicationManager.getApplication().invokeLater {
                    if (error != null) {
                        log.warn("[install] failed: ${error.message}")
                        Messages.showErrorDialog(project, error.message, DshBundle.message("bridge.install.title"))
                    } else {
                        // Keep actionable installer notices, not subprocess output dumps.
                        (out.first.lineSequence() + out.second.lineSequence())
                            .map { it.trim() }
                            .filter { it.contains("WARNING", ignoreCase = true) || it.contains("NOTE:") }
                            .distinct()
                            .take(10)
                            .forEach { log.warn("[install] " + it.take(500)) }
                        log.info("[install] completed: package=$pkg profile=acp-vscode")
                        s.profile = "acp-vscode"
                        Messages.showInfoMessage(
                            project,
                            DshBundle.message("bridge.install.done"),
                            DshBundle.message("bridge.install.title"),
                        )
                        onInstalled()
                        bridge.reconnect()
                    }
                }
            }
        }
    }
}
