/**
 * One-shot bridge installer/repairer, ported from dsh-lite-vscode
 * src/bridge/install.ts + installScript.ts. The bash template and the
 * cordis.patch.yml variants are vendored as resources. The patch variants
 * remain byte-identical to the VS Code sources; the install script additionally
 * reapplies the requested package spec so Repair also upgrades an older bridge.
 * Only __COMMAND__ is interpolated at run time; the package spec is passed as
 * a literal positional argument so shell substitution is never re-evaluated.
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

    private fun resource(name: String): String =
        BridgeInstall::class.java.getResourceAsStream("/bridge/$name")!!
            .readBytes().toString(StandardCharsets.UTF_8)

    fun buildVersionProbeScript(command: String): String =
        "$command --version 2>/dev/null | grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?' | head -n1"

    fun buildBridgeRegistryQueryScript(): String =
        "npm view 'dsh-vscode-bridge@>=0.1.1' version dshCompatibility " +
            "devDependencies.@deepseek-ai/dsh-agent --json --fetch-retries=1 " +
            "--fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=3000 --fetch-timeout=15000"

    /** Assemble the minimal install script for the target side. */
    fun buildInstallScript(command: String): String =
        resource("install-template.sh").replace("__COMMAND__", command)

    private fun targetShellArgs(arguments: List<String>, ideWorkspacePath: String?): List<String> {
        val s = DshSettingsState.getInstance()
        val wsl = com.dshlite.idea.launcher.usesWsl(s.runtime, SystemInfoRt.isWindows)
        val args = if (wsl) buildList {
            add("wsl.exe")
            val distro = com.dshlite.idea.launcher.wslDistroForWorkspace(s, ideWorkspacePath)
            if (distro != null) { add("-d"); add(distro) }
            add("-e"); add("bash"); add("-s")
        }.toMutableList() else mutableListOf("/bin/bash", "-s")
        if (arguments.isNotEmpty()) {
            args.add("--")
            args.addAll(arguments)
        }
        return args
    }

    /** Run a shell script over stdin on the target side (not argv: the preset rows exceed the Windows argv limit). */
    internal fun runScript(
        script: String,
        timeoutMs: Long,
        arguments: List<String> = emptyList(),
        ideWorkspacePath: String? = null,
    ): CompletableFuture<Pair<String, String>> {
        val future = CompletableFuture<Pair<String, String>>()
        ApplicationManager.getApplication().executeOnPooledThread {
            var proc: Process? = null
            try {
                proc = ProcessBuilder(targetShellArgs(arguments, ideWorkspacePath)).start()
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

    /**
     * Detect host → query compatible exact package → install without a version
     * confirmation. Every selection failure happens before profile mutation.
     */
    fun run(
        project: Project,
        bridge: BridgeManager,
        onInstalled: () -> Unit = {},
        importProfile: () -> Unit = {},
    ) {
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val probed = bridge.hostDshVersion ?: runCatching {
                runScript(buildVersionProbeScript(s.command), 30_000, ideWorkspacePath = project.basePath)
                    .get(35, TimeUnit.SECONDS).first.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val host = BridgeRegistry.parseDshVersion(probed)
            if (host == null) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        DshBundle.message("bridge.install.hostUnknown"),
                        DshBundle.message("bridge.install.title"),
                    )
                }
                return@executeOnPooledThread
            }
            val pkg = try {
                val response = runScript(buildBridgeRegistryQueryScript(), 45_000, ideWorkspacePath = project.basePath)
                    .get(50, TimeUnit.SECONDS).first
                BridgeRegistry.selectLatestCompatibleBridge(host, BridgeRegistry.parseBridgeRegistryResponse(response))
            } catch (e: Exception) {
                val cause = generateSequence<Throwable>(e) { (it as? java.util.concurrent.ExecutionException)?.cause }
                    .filterIsInstance<BridgeRegistryError>().firstOrNull()
                log.warn("[install] online bridge lookup failed: ${cause?.code ?: "registry/unavailable"}")
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        DshBundle.message(
                            if (cause?.code == BridgeRegistry.REGISTRY_NO_COMPATIBLE) "bridge.install.noCompatible"
                            else "bridge.install.registryFailed",
                            probed ?: "",
                        ),
                        DshBundle.message("bridge.install.title"),
                    )
                }
                return@executeOnPooledThread
            }
            log.info("[install] host dsh=$probed → registry-selected bridge spec: $pkg")
            install(project, bridge, s, pkg, onInstalled, importProfile)
        }
    }

    private fun install(
        project: Project,
        bridge: BridgeManager,
        s: DshSettingsState,
        pkg: String,
        onInstalled: () -> Unit,
        importProfile: () -> Unit,
    ) {
        runScript(buildInstallScript(s.command), 180_000, listOf(pkg), project.basePath).whenComplete { out, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    log.warn("[install] failed: ${error.message}")
                    Messages.showErrorDialog(project, error.message, DshBundle.message("bridge.install.title"))
                } else {
                    (out.first.lineSequence() + out.second.lineSequence())
                        .map { it.trim() }
                        .filter { it.contains("WARNING", ignoreCase = true) || it.contains("NOTE:") }
                        .distinct()
                        .take(10)
                        .forEach { log.warn("[install] " + it.take(500)) }
                    log.info("[install] completed: profile=acp-vscode")
                    s.profile = "acp-vscode"
                    Messages.showInfoMessage(
                        project,
                        DshBundle.message("bridge.install.done"),
                        DshBundle.message("bridge.install.title"),
                    )
                    try {
                        importProfile()
                    } catch (e: Exception) {
                        log.warn("[install] profile import could not be opened: ${e.message}")
                        Messages.showWarningDialog(
                            project,
                            DshBundle.message("bridge.install.importFailed"),
                            DshBundle.message("bridge.install.title"),
                        )
                    }
                    onInstalled()
                    bridge.reconnect()
                }
            }
        }
    }
}
