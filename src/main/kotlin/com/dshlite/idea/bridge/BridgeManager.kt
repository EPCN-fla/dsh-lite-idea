/**
 * Finds the dsh-vscode-bridge instance serving this workspace via the
 * discovery dir ($DSH_HOME/vscode-bridge/<pid>.json), keeps a live
 * BridgeClient, and re-broadcasts events. Ported from dsh-lite-vscode
 * src/bridge/manager.ts + discovery.ts.
 *
 * Topology notes:
 *  - local (Linux/macOS IDE): read the dir directly.
 *  - Windows IDE + WSL dsh: read discovery JSON through a target-side shell
 *    command; polling backs off when the workspace has no bridge.
 *  - TCP connects always go to 127.0.0.1 (WSL2 loopback forwarding).
 */
package com.dshlite.idea.bridge

import com.dshlite.idea.launcher.createLauncher
import com.dshlite.idea.settings.DshSettingsState
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

enum class BridgeState { OFF, CONNECTING, ON }

@Service(Service.Level.PROJECT)
class BridgeManager(private val project: Project) : Disposable {

    interface Listener {
        fun onState(state: BridgeState, capabilities: BridgeCapabilities?) {}
        fun onEvent(event: JsonObject) {} // raw bridge.event params: {kind, sessionId?, event?}
    }

    private val log = Logger.getInstance(BridgeManager::class.java)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dsh-bridge-poll").apply { isDaemon = true }
    }

    @Volatile
    var state: BridgeState = BridgeState.OFF
        private set

    @Volatile
    var client: BridgeClient? = null
        private set

    @Volatile
    var hostDshVersion: String? = null
        private set

    /** Last discovery outcome for the Doctor report. */
    @Volatile
    var lastDiscoveryInfo: String = "never attempted"
        private set

    private var connectedPid: Int? = null
    private var started = false
    @Volatile private var disposed = false

    /** Poll ticks to skip after consecutive misses: on Windows every
     *  discovery read spawns wsl.exe, so a workspace without a bridge must
     *  not cost a process every 3s forever (3s → 6s → 12s … capped at 48s). */
    @Volatile private var misses = 0
    @Volatile private var skipTicks = 0

    val isOn: Boolean get() = state == BridgeState.ON && client?.isOpen == true
    val capabilities: BridgeCapabilities? get() = client?.capabilities

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun fireState(s: BridgeState) {
        state = s
        val caps = capabilities
        listeners.forEach { it.onState(s, caps) }
    }

    fun start() {
        if (started) return
        started = true
        reconnect()
        // UNC/dir watching is unreliable across OS boundaries; polling is the primary channel.
        scheduler.scheduleWithFixedDelay({
            if (isOn) return@scheduleWithFixedDelay
            if (skipTicks > 0) { skipTicks--; return@scheduleWithFixedDelay }
            reconnect()
        }, 3, 3, TimeUnit.SECONDS)
    }

    // ---------- discovery ----------

    private fun settings() = DshSettingsState.getInstance()

    /** Read all discovery entries; the string is a diagnostic for the Doctor report. */
    private fun readDiscoveryEntries(): Pair<String, List<BridgeDiscoveryEntry>> {
        val s = settings()
        val target = if (com.dshlite.idea.launcher.usesWsl(s.runtime, SystemInfoRt.isWindows)) "wsl" else "local"
        val dshHomeOverride = s.envMap()["DSH_HOME"]?.takeIf { it.isNotBlank() }
        return when (target) {
            "wsl" -> readDiscoveryViaWsl(dshHomeOverride)
            else -> {
                val home = dshHomeOverride ?: "${System.getProperty("user.home")}/.dsh"
                val dir = Path.of(home, "vscode-bridge")
                val entries = readDiscoveryDir(dir)
                "dir=" + dir + " entries=" + entries.size to entries
            }
        }
    }

    /**
     * Windows IDE -> WSL dsh: read the discovery files through the same
     * wsl.exe bash channel the ACP spawn uses. The earlier UNC approach
     * (\\wsl.localhost\<distro>) needed a distro/home probe whose short-lived
     * wsl.exe call returned empty output on the user's machine; this path shares
     * the proven ACP spawn shape instead.
     */
    private fun readDiscoveryViaWsl(dshHomeOverride: String?): Pair<String, List<BridgeDiscoveryEntry>> {
        val s = settings()
        val dirExpr = if (dshHomeOverride != null) {
            com.dshlite.idea.launcher.ShellEnv.shellQuote("$dshHomeOverride/vscode-bridge")
        } else {
            "\"${'$'}{DSH_HOME:-${'$'}HOME/.dsh}/vscode-bridge\""
        }
        val script = "for f in " + dirExpr + "/*.json; do [ -f \"${'$'}f\" ] || continue; echo '<<<'; cat \"${'$'}f\"; done"
        val args = buildList {
            add("wsl.exe")
            if (s.wslDistro.isNotBlank()) { add("-d"); add(s.wslDistro) }
            add("-e"); add("bash"); add("-lc"); add(script)
        }
        return runCatching {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            // wsl.exe relays stdin into the VM and may hold the child open until
            // stdin closes — close it for this one-shot probe.
            runCatching { proc.outputStream.close() }
            // Drain concurrently with waitFor: pipe-buffer deadlock otherwise.
            val outFuture = com.dshlite.idea.util.ioAsync { proc.inputStream.bufferedReader().readText() }
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching "wsl discovery read timed out (15s)" to emptyList()
            }
            val out = outFuture.get(5, TimeUnit.SECONDS)
            val entries = out.split(Regex("(?m)^<<<$")).mapNotNull { chunk ->
                chunk.trim().takeIf { it.isNotEmpty() }?.let { text ->
                    runCatching { parseEntry(JsonParser.parseString(text).asJsonObject) }.getOrNull()
                }
            }
            // Keep a compact diagnostic, never discovery JSON (contains tokens).
            val info = "wsl-cat entries=" + entries.size + " exit=" + proc.exitValue()
            info to entries
        }.getOrElse { e ->
            log.warn("[bridge] wsl discovery read failed: ${e.message}")
            "wsl-cat failed: " + e.message to emptyList()
        }
    }

    private fun readDiscoveryDir(dir: Path): List<BridgeDiscoveryEntry> {
        if (!dir.isDirectory()) return emptyList()
        return runCatching {
            dir.listDirectoryEntries("*.json").mapNotNull { f ->
                runCatching { parseEntry(JsonParser.parseString(f.readText()).asJsonObject) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseEntry(o: JsonObject): BridgeDiscoveryEntry? {
        val port = o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotEmpty() } ?: return null
        val dirs = o.getAsJsonArray("directories")?.mapNotNull {
            it.takeIf { e -> e.isJsonPrimitive }?.asString
        } ?: return null // legacy per-workspace entries are incompatible
        return BridgeDiscoveryEntry(
            port = port,
            token = token,
            pid = o.get("pid")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            startedAt = parseStartedAt(o.get("startedAt")),
            dshVersion = o.get("dshVersion")?.takeIf { it.isJsonPrimitive }?.asString,
            directories = dirs,
        )
    }

    /** startedAt is epoch millis in early bridge versions, ISO-8601 text in newer ones. */
    private fun parseStartedAt(el: com.google.gson.JsonElement?): Long? {
        val p = el as? com.google.gson.JsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asLong
            p.isString -> runCatching { java.time.Instant.parse(p.asString).toEpochMilli() }.getOrNull()
            else -> null
        }
    }

    /** Pick the instance serving dshCwd (dsh-side path form); newest startedAt wins. */
    private fun pickEntry(entries: List<BridgeDiscoveryEntry>, dshCwd: String): BridgeDiscoveryEntry? {
        val ci = SystemInfoRt.isWindows && settings().runtime in setOf("windows", "local")
        fun norm(p: String): String {
            val n = p.trimEnd('/', '\\')
            return if (ci) n.lowercase() else n
        }
        val want = norm(dshCwd)
        return entries.filter { e -> e.directories.any { norm(it) == want } }
            .maxByOrNull { it.startedAt ?: 0 }
    }

    // ---------- connect ----------

    private val reconnectInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reconnect() {
        val app = ApplicationManager.getApplication()
        if (disposed || app.isDisposed || project.isDisposed) return
        // The 3s poll plus event-driven reconnects must not stack: a second
        // concurrent connect would leak a socket and double-fire state.
        if (!reconnectInFlight.compareAndSet(false, true)) return
        app.executeOnPooledThread {
            try {
                reconnectUnsafe()
            } catch (e: Exception) {
                log.warn("[bridge] reconnect failed: ${e.message}")
                teardown("connect failed")
            } finally {
                reconnectInFlight.set(false)
            }
        }
    }

    private fun reconnectUnsafe() {
        if (disposed || project.isDisposed) return
        val basePath = project.basePath ?: run {
            lastDiscoveryInfo = "no workspace folder"
            teardown("no workspace folder"); return
        }
        val dshCwd = createLauncher(settings()).toDshPath(basePath)
        val (discInfo, entries) = readDiscoveryEntries()
        val entry = pickEntry(entries, dshCwd)
        if (entry == null) {
            lastDiscoveryInfo = discInfo + " want=" + dshCwd + " (no match)"
            misses = (misses + 1).coerceAtMost(5)
            skipTicks = (1 shl (misses - 1)) - 1
            teardown("no bridge entry for this workspace")
            return
        }
        misses = 0
        skipTicks = 0
        lastDiscoveryInfo = discInfo + " picked pid=" + entry.pid + " port=" + entry.port
        val current = client
        if (current != null && current.isOpen && connectedPid == entry.pid) return

        fireState(BridgeState.CONNECTING)
        try {
            // The onClose identity check needs the client, which only exists
            // after connect() returns — close over a holder.
            val holder = arrayOfNulls<BridgeClient>(1)
            val c = BridgeClient.connect(
                entry,
                onEvent = { e -> listeners.forEach { it.onEvent(e) } },
                // Only the CURRENT client's close may tear down state — after a
                // reconnect swap, the old socket's close must not kill the new one.
                onClose = { if (client === holder[0] && holder[0] != null) teardown("connection closed") },
            )
            synchronized(this) {
                holder[0] = c
                if (disposed || project.isDisposed || !c.isOpen) {
                    c.close()
                    return
                }
                current?.close()
                client = c
                connectedPid = entry.pid
                hostDshVersion = c.handshake?.dshVersion ?: entry.dshVersion
                log.info("[bridge] connected pid=${entry.pid} v${c.handshake?.version} dsh=${hostDshVersion ?: "unknown"}")
                fireState(BridgeState.ON)
            }
        } catch (e: Exception) {
            log.warn("[bridge] connect pid=${entry.pid} failed: ${e.message}")
            teardown("connect failed")
        }
    }

    @Synchronized
    fun teardown(reason: String) {
        if (state == BridgeState.OFF && client == null) return
        log.info("[bridge] off ($reason)")
        client?.close()
        client = null
        connectedPid = null
        hostDshVersion = null
        fireState(BridgeState.OFF)
    }

    override fun dispose() {
        disposed = true
        scheduler.shutdownNow()
        teardown("dispose")
    }

    companion object {
        fun getInstance(project: Project): BridgeManager = project.getService(BridgeManager::class.java)
    }
}
