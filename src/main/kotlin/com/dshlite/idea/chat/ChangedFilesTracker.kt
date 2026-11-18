package com.dshlite.idea.chat

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.SessionUpdateData
import com.dshlite.idea.launcher.createLauncher
import com.dshlite.idea.settings.DshSettingsState
import com.dshlite.idea.util.destroyProcessTree
import com.dshlite.idea.util.ioAsync
import com.dshlite.idea.util.readBytesBounded
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val GIT_TIMEOUT_MS = 15_000L
private const val MAX_GIT_OUTPUT_BYTES = 16 * 1024 * 1024

/** A workspace file attributed to a particular main-session conversation. */
data class ChangedFile(val path: String, val label: String, val kind: ChangedFileKind)

enum class ChangedFileKind { CREATED, MODIFIED }

data class PorcelainEntry(val path: String, val created: Boolean)

/** Extract dsh-side paths from ACP tool_call/tool_call_update diff content. */
fun extractDiffPaths(update: SessionUpdateData): List<String> {
    if (update.kind != "tool_call" && update.kind != "tool_call_update") return emptyList()
    val content = update.content?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    val paths = ArrayList<String>()
    for (element in content) {
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val path = obj.get("path")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (obj.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString == "diff" &&
            !path.isNullOrEmpty()
        ) {
            paths.add(path)
        }
    }
    return paths
}

/**
 * Parse `git status --porcelain=v1 -z`. Rename/copy records contain a second
 * NUL-delimited field for the original path; only the destination is reported.
 */
fun parsePorcelainZ(output: String): List<PorcelainEntry> {
    val fields = output.split('\u0000').filter { it.isNotEmpty() }
    val entries = ArrayList<PorcelainEntry>()
    var index = 0
    while (index < fields.size) {
        val field = fields[index]
        if (field.length < 4) {
            index++
            continue
        }
        val x = field[0]
        val y = field[1]
        entries.add(PorcelainEntry(field.substring(3), created = x == '?' || x == 'A' || y == 'A'))
        if (x == 'R' || x == 'C' || y == 'R' || y == 'C') index++
        index++
    }
    return entries
}

/** Complete path segments are ignored; lookalikes such as `distinct` are not. */
fun isIgnoredPath(path: String): Boolean {
    val segments = path.replace('\\', '/').split('/')
    if (segments.any { it == ".git" || it == "node_modules" || it == "dist" || it == "out" || it == ".vscode-test" }) {
        return true
    }
    return segments.lastOrNull()?.endsWith(".git") == true
}

internal fun interface GitRunner {
    /** Null means command failure, timeout, or excessive output. Empty bytes are a successful empty response. */
    fun run(root: Path, arguments: List<String>, maxOutputBytes: Int): ByteArray?
}

internal object ProcessGitRunner : GitRunner {
    override fun run(root: Path, arguments: List<String>, maxOutputBytes: Int): ByteArray? {
        var process: Process? = null
        try {
            val started = ProcessBuilder(listOf("git") + arguments)
                .directory(root.toFile())
                .start()
            process = started
            // Drain both pipes concurrently: a large status must not deadlock git.
            val stdout = ioAsync("dsh-git-stdout") { readBytesBounded(started.inputStream, maxOutputBytes) }
            ioAsync("dsh-git-stderr") { readBytesBounded(started.errorStream, 64 * 1024) }
            if (!started.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                destroyProcessTree(started, forcibly = true)
                return null
            }
            if (started.exitValue() != 0) return null
            return stdout.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            process?.let { if (it.isAlive) destroyProcessTree(it, forcibly = true) }
            return null
        } finally {
            process?.let { if (it.isAlive) destroyProcessTree(it, forcibly = true) }
        }
    }
}

private class SessionChanges {
    val files = LinkedHashMap<Path, ChangedFileKind>()
    var baselineDirty: Set<Path>? = null
    var promptVersion = 0L
}

private data class SessionToken(val id: String, val state: SessionChanges)
private data class SettleToken(val id: String, val state: SessionChanges, val promptVersion: Long)

/** Synchronized, platform-free session state. Async callers present identity tokens. */
private class ChangedFilesState(private val root: Path) {
    private val sessions = HashMap<String, SessionChanges>()
    private val busySessions = HashSet<String>()

    @Synchronized
    fun startSession(id: String): SessionToken? {
        if (sessions.containsKey(id)) return null
        val state = SessionChanges()
        sessions[id] = state
        return SessionToken(id, state)
    }

    @Synchronized
    fun completeBaseline(token: SessionToken, dirty: Set<Path>?) {
        if (sessions[token.id] === token.state) token.state.baselineDirty = dirty
    }

    @Synchronized
    fun promptStarted(id: String) {
        sessions[id]?.let {
            it.promptVersion++
            busySessions.add(id)
        }
    }

    @Synchronized
    fun promptSettled(id: String): SettleToken? {
        busySessions.remove(id)
        val state = sessions[id] ?: return null
        return SettleToken(id, state, state.promptVersion)
    }

    @Synchronized
    fun baselineFor(token: SettleToken): Set<Path>? {
        if (sessions[token.id] !== token.state || token.state.promptVersion != token.promptVersion) return null
        return token.state.baselineDirty
    }

    @Synchronized
    fun recordVfs(path: Path, kind: ChangedFileKind): List<Pair<String, List<ChangedFile>>> {
        if (busySessions.isEmpty()) return emptyList()
        val changed = ArrayList<Pair<String, List<ChangedFile>>>()
        for (id in busySessions) {
            val state = sessions[id] ?: continue
            if (state.files[path] != ChangedFileKind.CREATED) state.files[path] = kind
            changed.add(id to filesForLocked(state))
        }
        return changed
    }

    @Synchronized
    fun recordAcp(id: String, paths: List<Path>): List<ChangedFile>? {
        val state = sessions[id] ?: return null
        var changed = false
        for (path in paths) {
            if (!state.files.containsKey(path)) {
                state.files[path] = ChangedFileKind.MODIFIED
                changed = true
            }
        }
        return if (changed) filesForLocked(state) else null
    }

    @Synchronized
    fun replaceFromGit(token: SettleToken, files: Map<Path, ChangedFileKind>): List<ChangedFile>? {
        if (sessions[token.id] !== token.state || token.state.promptVersion != token.promptVersion) return null
        token.state.files.clear()
        token.state.files.putAll(files)
        return filesForLocked(token.state)
    }

    @Synchronized
    fun filesFor(id: String): List<ChangedFile> = sessions[id]?.let(::filesForLocked).orEmpty()

    @Synchronized
    fun clear(id: String): Boolean {
        busySessions.remove(id)
        return sessions.remove(id) != null
    }

    @Synchronized
    fun clearAll() {
        busySessions.clear()
        sessions.clear()
    }

    private fun filesForLocked(state: SessionChanges): List<ChangedFile> =
        state.files.entries
            .sortedBy { it.key.toString() }
            .map { (path, kind) ->
                ChangedFile(path.toString(), root.relativize(path).joinToString("/"), kind)
            }
}

/**
 * Three complementary channels: VFS writes during busy prompts, ACP diff
 * payloads, and a final net git-status snapshot filtered by session-start dirt.
 */
class ChangedFilesTracker private constructor(
    private val workspaceRoot: Path?,
    private val project: Project?,
    private val fromDshPath: (String) -> String,
    private val gitRunner: GitRunner,
    private val background: Executor,
    private val onChanged: (sessionId: String, files: List<ChangedFile>) -> Unit,
) : Disposable {

    constructor(
        project: Project,
        onChanged: (sessionId: String, files: List<ChangedFile>) -> Unit,
    ) : this(
        workspaceRoot = project.basePath?.let { Path.of(it) },
        project = project,
        fromDshPath = { path ->
            createLauncher(DshSettingsState.getInstance(), project.basePath).fromDshPath(path)
        },
        gitRunner = ProcessGitRunner,
        background = Executor { runnable -> ApplicationManager.getApplication().executeOnPooledThread(runnable) },
        onChanged = onChanged,
    )

    internal constructor(
        workspaceRoot: Path,
        fromDshPath: (String) -> String,
        gitRunner: GitRunner,
        background: Executor,
        onChanged: (sessionId: String, files: List<ChangedFile>) -> Unit,
    ) : this(workspaceRoot, null, fromDshPath, gitRunner, background, onChanged)

    private val state = workspaceRoot?.toAbsolutePath()?.normalize()?.let(::ChangedFilesState)
    private val baselineJobs = HashMap<String, CompletableFuture<Void>>()

    @Volatile private var disposed = false
    @Volatile private var gitRepo: Boolean? = null
    private var watcherConnection: MessageBusConnection? = null

    @Synchronized
    fun startSession(sessionId: String) {
        if (disposed || baselineJobs.containsKey(sessionId)) return
        val tracking = state ?: return
        val token = tracking.startSession(sessionId) ?: return
        val job = CompletableFuture<Void>()
        baselineJobs[sessionId] = job
        background.execute {
            try {
                val dirty = if (isGitRepo()) gitStatus()?.mapNotNullTo(HashSet()) { workspacePath(it.path) } else null
                tracking.completeBaseline(token, dirty)
            } finally {
                job.complete(null)
                synchronized(this@ChangedFilesTracker) {
                    if (baselineJobs[sessionId] === job) baselineJobs.remove(sessionId)
                }
            }
        }
    }

    fun promptStarted(sessionId: String) {
        if (disposed) return
        state?.promptStarted(sessionId)
        ensureWatcher()
    }

    fun promptSettled(sessionId: String) {
        if (disposed) return
        val token = state?.promptSettled(sessionId) ?: return
        val baselineJob = synchronized(this) { baselineJobs[sessionId] }
        val refresh = Runnable { refreshFromGit(token) }
        if (baselineJob == null) background.execute(refresh)
        else baselineJob.thenRunAsync(refresh, background)
    }

    /** ACP mapping can invoke wslpath on Windows, so it never runs on the EDT. */
    fun ingestUpdate(sessionId: String, update: SessionUpdateData) {
        if (disposed || extractDiffPaths(update).isEmpty()) return
        background.execute {
            if (disposed) return@execute
            val paths = extractDiffPaths(update).mapNotNull { raw ->
                runCatching { fromDshPath(raw) }.getOrNull()?.let(::workspacePath)
            }
            state?.recordAcp(sessionId, paths)?.let { files -> fire(sessionId, files) }
        }
    }

    /** Visible for the platform listener and for platform-free tracker tests. */
    internal fun recordFileEvent(path: String, kind: ChangedFileKind) {
        if (disposed) return
        val file = workspacePath(path) ?: return
        state?.recordVfs(file, kind)?.forEach { (id, files) -> fire(id, files) }
    }

    fun filesFor(sessionId: String): List<ChangedFile> = state?.filesFor(sessionId).orEmpty()

    @Synchronized
    fun clear(sessionId: String) {
        baselineJobs.remove(sessionId)
        if (state?.clear(sessionId) == true) fire(sessionId, emptyList())
    }

    private fun refreshFromGit(token: SettleToken) {
        if (disposed) return
        val tracking = state ?: return
        val baseline = tracking.baselineFor(token) ?: return
        if (!isGitRepo()) return
        // Failure is deliberately different from a successful clean status.
        val now = gitStatus() ?: return
        val final = LinkedHashMap<Path, ChangedFileKind>()
        for (entry in now) {
            val path = workspacePath(entry.path) ?: continue
            if (path !in baseline) final[path] = if (entry.created) ChangedFileKind.CREATED else ChangedFileKind.MODIFIED
        }
        tracking.replaceFromGit(token, final)?.let { files -> fire(token.id, files) }
    }

    private fun workspacePath(raw: String): Path? {
        val root = workspaceRoot?.toAbsolutePath()?.normalize() ?: return null
        val parsed = runCatching { Path.of(raw) }.getOrNull() ?: return null
        val path = (if (parsed.isAbsolute) parsed else root.resolve(parsed)).normalize()
        if (path == root || !path.startsWith(root)) return null
        val relative = root.relativize(path).joinToString("/")
        return if (isIgnoredPath(relative)) null else path
    }

    private fun isGitRepo(): Boolean {
        gitRepo?.let { return it }
        val root = workspaceRoot ?: return false
        val output = gitRunner.run(root, listOf("rev-parse", "--is-inside-work-tree"), 4 * 1024)
        val result = output?.toString(Charsets.UTF_8)?.trim() == "true"
        gitRepo = result
        return result
    }

    private fun gitStatus(): List<PorcelainEntry>? {
        val root = workspaceRoot ?: return null
        val output = gitRunner.run(
            root,
            listOf("status", "--porcelain=v1", "-z", "--untracked-files=all"),
            MAX_GIT_OUTPUT_BYTES,
        ) ?: return null
        return parsePorcelainZ(output.toString(Charsets.UTF_8))
    }

    @Synchronized
    private fun ensureWatcher() {
        if (watcherConnection != null || project == null || workspaceRoot == null || disposed) return
        val application = ApplicationManager.getApplication() ?: return
        watcherConnection = application.messageBus.connect().apply {
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        val kind = when (event) {
                            is VFileCreateEvent, is VFileCopyEvent -> ChangedFileKind.CREATED
                            is VFileContentChangeEvent -> ChangedFileKind.MODIFIED
                            // Deletes, moves, and property events are reconciled by
                            // the final net git status; they are not watcher writes.
                            else -> continue
                        }
                        val virtualFile = event.file
                        if (virtualFile?.isDirectory == true) continue
                        recordFileEvent(virtualFile?.path ?: event.path, kind)
                    }
                }
            })
        }
    }

    /** Open a HEAD/current diff; without git there is no baseline, so open the file. */
    fun openDiff(file: ChangedFile) {
        val currentProject = project ?: return
        if (disposed) return
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)
        if (virtualFile == null || virtualFile.isDirectory) {
            Messages.showInfoMessage(currentProject, DshBundle.message("changes.missing", file.label), DshBundle.message("changes.diffTitle", file.label))
            return
        }
        background.execute {
            val useDiff = !disposed && isGitRepo()
            val baseline = if (!useDiff) {
                null
            } else if (file.kind == ChangedFileKind.CREATED) {
                ByteArray(0)
            } else {
                workspaceRoot?.let { root ->
                    gitRunner.run(root, listOf("show", "HEAD:${file.label}"), MAX_GIT_OUTPUT_BYTES)
                } ?: ByteArray(0)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || currentProject.isDisposed) return@invokeLater
                if (!useDiff) {
                    FileEditorManager.getInstance(currentProject).openFile(virtualFile, true)
                    return@invokeLater
                }
                val factory = DiffContentFactory.getInstance()
                val before = factory.createFromBytes(currentProject, baseline ?: ByteArray(0), virtualFile)
                val after = factory.create(currentProject, virtualFile)
                DiffManager.getInstance().showDiff(
                    currentProject,
                    SimpleDiffRequest(
                        DshBundle.message("changes.diffTitle", file.label),
                        before,
                        after,
                        DshBundle.message("changes.head"),
                        DshBundle.message("changes.current"),
                    ),
                )
            }, ModalityState.any())
        }
    }

    private fun fire(sessionId: String, files: List<ChangedFile>) {
        if (!disposed) runCatching { onChanged(sessionId, files) }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        synchronized(this) {
            watcherConnection?.disconnect()
            watcherConnection = null
            baselineJobs.clear()
        }
        state?.clearAll()
    }

}
