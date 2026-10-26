package com.dshlite.idea.acp

import com.dshlite.idea.launcher.Launcher
import com.dshlite.idea.launcher.createLauncher
import com.dshlite.idea.util.destroyProcessTree
import com.dshlite.idea.util.readBytesBounded
import com.dshlite.idea.util.readUtf8Bounded
import com.dshlite.idea.settings.DshSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

enum class ConnectionState { STARTING, READY, CLOSED }

/**
 * Owns the dsh ACP connection shared by all UI surfaces of a project.
 * UI-free: permission brokering is delegated to a responder set by the UI layer.
 */
@Service(Service.Level.PROJECT)
class AcpService(private val project: Project) : Disposable {

    interface StateListener {
        fun onState(state: ConnectionState, detail: String?)
    }

    interface UpdateListener {
        fun onUpdate(update: SessionUpdateData)
    }

    @Volatile
    private var client: AcpClient? = null
    private var starting: CompletableFuture<AcpClient>? = null
    private var startingProcess: Process? = null
    @Volatile private var disposed = false

    @Volatile
    var state: ConnectionState = ConnectionState.CLOSED
        private set

    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val updateListeners = CopyOnWriteArrayList<Pair<String, UpdateListener>>() // sessionId -> listener
    private val ideLog = com.intellij.openapi.diagnostic.Logger.getInstance(AcpService::class.java)

    /** Installed by the UI layer; runs the permission dialog. */
    @Volatile
    var permissionResponder: ((PermissionRequestData) -> CompletableFuture<String?>)? = null

    fun addStateListener(l: StateListener) { stateListeners.add(l) }
    fun removeStateListener(l: StateListener) { stateListeners.remove(l) }

    fun addUpdateListener(sessionId: String, l: UpdateListener) {
        updateListeners.add(sessionId to l)
    }

    fun removeUpdateListener(l: UpdateListener) {
        updateListeners.removeIf { it.second === l }
    }

    private fun fireState(s: ConnectionState, detail: String? = null) {
        state = s
        stateListeners.forEach { it.onState(s, detail) }
    }

    val isReady: Boolean get() = client?.isAlive() == true

    private fun launcher(): Launcher = createLauncher(DshSettingsState.getInstance())

    fun workspaceIdePath(): String =
        project.basePath ?: throw AcpException("Open a project folder first — dsh needs an absolute workspace cwd.")

    private fun workspaceDshPath(): String = launcher().toDshPath(workspaceIdePath())

    /** Workspace cwd in dsh-side path form (for bridge-side filtering). */
    fun workspaceDshCwd(): String = workspaceDshPath()

    @Synchronized
    fun ensureClient(): CompletableFuture<AcpClient> {
        if (disposed || project.isDisposed) return CompletableFuture.failedFuture(AcpException("project is disposed"))
        client?.takeIf { it.isAlive() }?.let { return CompletableFuture.completedFuture(it) }
        starting?.let { return it }
        val future = CompletableFuture<AcpClient>()
        starting = future
        fireState(ConnectionState.STARTING)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val launcher = launcher()
                val spec = launcher.buildLaunchSpec(workspaceIdePath())
                ideLog.info("[dsh] starting: ${launcher.label}")
                // The shell command embeds user environment values; never
                // log its body (single-quote redaction misses escaped quotes).
                val pb = ProcessBuilder(spec.command)
                spec.cwd?.let { pb.directory(java.io.File(it)) }
                pb.environment().putAll(spec.env)
                val proc = pb.start()
                synchronized(this) {
                    if (starting !== future || disposed || project.isDisposed) {
                        destroyProcessTree(proc, forcibly = true)
                        return@executeOnPooledThread
                    }
                    startingProcess = proc
                }
                val version = pluginVersion()
                // The exit watcher needs the client identity, but the client only
                // exists after start() returns — close over a holder.
                val holder = arrayOfNulls<AcpClient>(1)
                val c = AcpClient.start(proc, version, object : AcpClientListener {
                    override fun onSessionUpdate(sessionId: String, update: SessionUpdateData) {
                        if (client !== holder[0] || holder[0] == null) return
                        updateListeners.forEach { (sid, l) -> if (sid == sessionId) l.onUpdate(update) }
                    }

                    override fun onPermission(request: PermissionRequestData): CompletableFuture<String?> =
                        permissionResponder?.invoke(request) ?: CompletableFuture.completedFuture(null)

                    override fun onExit(code: Int, stderrTail: String) {
                        // Only the CURRENT client's exit may move the state: after
                        // reset()+ensureClient() the old process's exit thread would
                        // otherwise wipe the new live client.
                        synchronized(this@AcpService) {
                            if (client === holder[0] && holder[0] != null) {
                                if (code == 0) {
                                    ideLog.info("[dsh] exited: code=0")
                                } else {
                                    val detail = stderrTail.trim().takeLast(600)
                                    ideLog.warn("[dsh] exited: code=$code" + if (detail.isEmpty()) "" else "\n$detail")
                                }
                                client = null
                                fireState(ConnectionState.CLOSED, "exit $code")
                            }
                        }
                    }
                })
                synchronized(this) {
                    if (starting !== future || disposed || project.isDisposed || !c.isAlive()) {
                        c.close()
                        future.completeExceptionally(AcpException("startup was superseded or the process exited"))
                        return@executeOnPooledThread
                    }
                    holder[0] = c
                    client = c
                    starting = null
                    startingProcess = null
                    ideLog.info("[dsh] connected: ${c.agentIdentity?.name} ${c.agentIdentity?.version ?: ""}")
                    fireState(ConnectionState.READY)
                    future.complete(c)
                }
            } catch (e: Exception) {
                synchronized(this) {
                    if (starting === future) {
                        startingProcess?.let { destroyProcessTree(it, forcibly = true) }
                        startingProcess = null
                        starting = null
                        ideLog.warn("[dsh] startup failed: ${e.message}")
                        fireState(ConnectionState.CLOSED, e.message)
                    }
                }
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun newSession(): CompletableFuture<NewSessionData> =
        ensureClient().thenCompose { it.newSession(workspaceDshPath()) }

    fun listSessions(): CompletableFuture<Pair<List<SessionInfoData>, String?>> =
        ensureClient().thenCompose { active ->
            val cwd = workspaceDshPath()
            val rows = LinkedHashMap<String, SessionInfoData>()
            val cursors = HashSet<String>()
            fun page(cursor: String?): CompletableFuture<Pair<List<SessionInfoData>, String?>> =
                active.listSessions(cwd, cursor).thenCompose { (sessions, next) ->
                    sessions.filter { it.sessionId.isNotBlank() }.forEach { rows[it.sessionId] = it }
                    if (next != null && cursors.add(next)) page(next)
                    else CompletableFuture.completedFuture(rows.values.toList() to null)
                }
            page(null)
        }

    fun resumeSession(sessionId: String): CompletableFuture<List<ConfigOption>> =
        ensureClient().thenCompose { it.resumeSession(sessionId, workspaceDshPath()) }

    fun prompt(sessionId: String, blocks: List<ContentBlock>): CompletableFuture<String> =
        ensureClient().thenCompose { it.prompt(sessionId, blocks) }

    fun cancel(sessionId: String) {
        client?.takeIf { it.isAlive() }?.cancel(sessionId)
    }

    fun setConfigOption(sessionId: String, configId: String, value: String): CompletableFuture<List<ConfigOption>> =
        ensureClient().thenCompose { it.setConfigOption(sessionId, configId, value) }

    fun closeSession(sessionId: String) {
        client?.takeIf { it.isAlive() }?.closeSession(sessionId)
    }

    @Synchronized
    fun reset() {
        val previousStart = starting
        starting = null
        startingProcess?.let { destroyProcessTree(it, forcibly = true) }
        startingProcess = null
        val previousClient = client
        client = null
        previousClient?.close()
        previousStart?.completeExceptionally(AcpException("dsh connection was reset"))
        fireState(ConnectionState.CLOSED)
    }

    /** Run a POSIX shell script on the target side; fails on native-Windows targets. */
    fun runTargetShell(script: String, timeoutMs: Long = 10_000): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val spec = launcher().buildShellSpec(script)
            ?: return future.also { it.completeExceptionally(AcpException("no POSIX shell on a native-Windows target")) }
        ApplicationManager.getApplication().executeOnPooledThread {
            var proc: Process? = null
            try {
                val builder = ProcessBuilder(spec.command).redirectErrorStream(true)
                spec.cwd?.let { builder.directory(java.io.File(it)) }
                builder.environment().putAll(spec.env)
                val process = builder.start()
                proc = process
                // wsl.exe relays stdin into the VM and may not let the child exit
                // until stdin closes — one-shot probes must close it immediately.
                runCatching { process.outputStream.close() }
                // Drain stdout CONCURRENTLY: cordis.patch.yml alone is ~28KB while
                // a Windows pipe buffer is ~4KB — reading only after waitFor()
                // deadlocks the child mid-write (this was the real effort-pin
                // blocker; Linux's 64KB buffer masked it in testing).
                val outFuture = com.dshlite.idea.util.ioAsync { readUtf8Bounded(process.inputStream, MAX_SHELL_OUTPUT_BYTES) }
                if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    destroyProcessTree(process, forcibly = true)
                    future.completeExceptionally(AcpException("target shell timed out"))
                    return@executeOnPooledThread
                }
                val out = outFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
                if (process.exitValue() != 0) throw AcpException("target shell exited ${process.exitValue()}")
                future.complete(out)
            } catch (e: Exception) {
                proc?.let { destroyProcessTree(it, forcibly = true) }
                future.completeExceptionally(e)
            }
        }
        return future
    }

    /** Run the diagnostic probe; returns combined stdout. */
    fun doctor(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val spec = launcher().buildDoctorSpec()
                val builder = ProcessBuilder(spec.command).redirectErrorStream(true)
                spec.cwd?.let { builder.directory(java.io.File(it)) }
                builder.environment().putAll(spec.env)
                val proc = builder.start()
                // See runTargetShell: one-shot wsl.exe probes must close stdin,
                // and stdout must drain concurrently (pipe-buffer deadlock).
                runCatching { proc.outputStream.close() }
                val outFuture = com.dshlite.idea.util.ioAsync { readBytesBounded(proc.inputStream, MAX_DOCTOR_OUTPUT_BYTES) }
                if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    destroyProcessTree(proc, forcibly = true)
                    future.completeExceptionally(AcpException("doctor timed out after 30s"))
                    return@executeOnPooledThread
                }
                val raw = outFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val text = String(raw).replace("\r\n", "\n").trim()
                // Never include spec.command here: it can contain exported credentials.
                val body = if (text.isEmpty()) {
                    "(no output — exit=" + proc.exitValue() + ", bytes=" + raw.size + ")"
                } else text
                future.complete(body)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    override fun dispose() {
        disposed = true
        reset()
    }

    companion object {
        private const val MAX_SHELL_OUTPUT_BYTES = 32 * 1024 * 1024
        private const val MAX_DOCTOR_OUTPUT_BYTES = 1024 * 1024

        fun getInstance(project: Project): AcpService = project.getService(AcpService::class.java)

        private fun pluginVersion(): String = runCatching {
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.dshlite.idea")
            )?.version ?: "dev"
        }.getOrDefault("dev")
    }
}
