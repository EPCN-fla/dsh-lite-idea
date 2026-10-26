package com.dshlite.idea.ui

import com.dshlite.idea.DshBundle
import com.dshlite.idea.acp.AcpService
import com.dshlite.idea.acp.ConfigOption
import com.dshlite.idea.acp.ConnectionState
import com.dshlite.idea.acp.ContentBlock
import com.dshlite.idea.acp.PROVIDER_DEFAULT_EFFORT_VALUE
import com.dshlite.idea.acp.SessionUpdateData
import com.dshlite.idea.acp.chooseEffort
import com.dshlite.idea.acp.collectEffortDefaults
import com.dshlite.idea.acp.findEffortOption
import com.dshlite.idea.acp.findModelOption
import com.dshlite.idea.acp.modelIdOf
import com.dshlite.idea.bridge.BridgeCapabilities
import com.dshlite.idea.bridge.BridgeInstall
import com.dshlite.idea.bridge.BridgeManager
import com.dshlite.idea.bridge.BridgeState
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Main chat tool window content: toolbar, config bar, message list, composer. */
class ChatPanel(
    private val project: Project,
    private val titleLabel: javax.swing.JLabel,
) : JPanel(BorderLayout()), Disposable {

    private val service = AcpService.getInstance(project)

    @Volatile private var disposed = false

    /** Deliver updates during permission/session dialogs, while this panel is alive. */
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) block()
        }, com.intellij.openapi.application.ModalityState.any())
    }
    private val bridge = BridgeManager.getInstance(project)
    private val configSyncFlow = com.dshlite.idea.bridge.ConfigSyncFlow(project, bridge, ::onConfigImported)
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ChatPanel::class.java)

    /* ------------------------------ entries ------------------------------ */

    private open class Entry(val component: JComponent)

    /** A streamed message: chunks accumulate in [buffer], [render] repaints it. */
    private class TextEntry(component: JComponent, val render: (String) -> Unit) : Entry(component) {
        val buffer = StringBuilder()
        var dirty = false
    }

    private class ToolEntry(val card: ToolCard) : Entry(card)

    /** The transcript: tracks the viewport width (wrapping), scrolls vertically. */
    private val entriesPanel = MessageList()
    private val scroll = JBScrollPane(entriesPanel).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }
    /** Follow new output only while the user is at the bottom (VS Code behavior). */
    private var autoScroll = true
    private var lastScrollValue = 0
    private val welcomePanel = WelcomePanel(
        project,
        onResume = { session ->
            resume(session.sessionId, title = session.title)
        },
        onBrowseSessions = { openSessions() },
    )
    private val centerCards = JPanel(java.awt.CardLayout())
    private var showingWelcome = true

    private val entries = ArrayList<Entry>()
    private val toolEntries = HashMap<String, ToolEntry>()
    private var streamingEntry: TextEntry? = null
    private var streamingKind: String? = null
    private var planCard: TodoCard? = null
    /** "Loading history…" note shown while a resumed transcript is fetched. */
    private var historyPlaceholder: Entry? = null
        set(value) {
            field = value
            updateSendAvailability()
        }

    private val renderTimer = Timer(150) { flushDirty() }.apply { isRepeats = true }

    /* ------------------------------ chrome ------------------------------- */

    private val usageLabel = JLabel(" ")
    private val configBar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isVisible = false }
    private val composer = JBTextArea(4, 40)
    /** Merged send/stop disc button at the composer rail's right end. */
    private val sendStopButton = RoundButton()

    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var busy = false
    /** Session the in-flight prompt belongs to; cancel targets it, not the
     *  click-time selection (the user may have switched sessions since). */
    @Volatile
    private var busySessionId: String? = null
    private var configOptions: List<ConfigOption> = emptyList()
    private var updatingConfigBar = false

    /* ------------------------------ bridge ------------------------------- */

    private data class PresetInfo(val id: String, val name: String, val isDefault: Boolean, val broken: String?)

    /** Presets the host rejected at mount time (learned, persisted app-wide). */
    private val failedPresets: MutableSet<String> by lazy {
        (com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue("dsh.brokenPresets") ?: "")
            .split(',').filter { it.isNotBlank() }.toMutableSet()
    }

    /** Positive import evidence, scoped to this runtime/home/profile rather than bridge capability. */
    private var cordisHostSupportImported: Boolean = run {
        val profile = com.dshlite.idea.settings.DshSettingsState.getInstance().profile
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(cordisSupportKey(profile)).toBoolean()
    }

    private fun cordisSupportKey(profile: String): String {
        val s = com.dshlite.idea.settings.DshSettingsState.getInstance()
        val identity = listOf(s.runtime, s.wslDistro, s.envMap()["DSH_HOME"].orEmpty(), profile)
            .joinToString("\u0000")
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.toByteArray(Charsets.UTF_8))
        return "dsh.cordisHostSupport.$encoded"
    }

    private fun onConfigImported(imported: com.dshlite.idea.bridge.ConfigSyncSummary) {
        val importedPresets = imported.presets.toSet()
        if (importedPresets.isEmpty()) return
        if ("cordis" in importedPresets) {
            com.intellij.ide.util.PropertiesComponent.getInstance()
                .setValue(cordisSupportKey(imported.target), true)
            val currentProfile = com.dshlite.idea.settings.DshSettingsState.getInstance().profile
            if (imported.target == currentProfile) cordisHostSupportImported = true
        }
        if (failedPresets.removeAll(importedPresets)) {
            com.intellij.ide.util.PropertiesComponent.getInstance()
                .setValue("dsh.brokenPresets", failedPresets.joinToString(","))
        }
        refreshPresets()
    }

    private data class PermissionMode(val value: String, val name: String)

    private var presets: List<PresetInfo> = emptyList()
    private var currentPreset: String? = null
    private var permModes: List<PermissionMode> = emptyList()
    private var currentPermMode: String? = null
    @Volatile private var bridgeSubscribedSession: String? = null
    @Volatile private var bridgeSubscribedClient: com.dshlite.idea.bridge.BridgeClient? = null
    private val bridgeAttachExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-bridge-attach").apply { isDaemon = true }
    }

    /* --------------------- slash commands & skills ----------------------- */

    /** model id -> defaultEffort, read once from the target-side dsh config files. */
    private var effortDefaults: MutableMap<String, String>? = null

    private var nativeCommands: List<SlashCommand> = emptyList()
    private var skills: List<SkillInfo> = emptyList()
    private val slashPopup = SlashPopup({ composer }, ::onSlashPick)
    private val filePopup = FileMentionPopup(project, { composer }, ::onFilePick)
    /** Display name -> IDE-side absolute path for @-mentions in the composer. */
    private val pendingFiles = java.util.LinkedHashMap<String, String>()
    /** Live config-bar combos by kind, so local slash commands can open them. */
    private var railSelects: Map<String, RailSelect> = emptyMap()
    /** Permission mode icon button (lock) with a checkmarked popup. */
    private val permissionButton = JButton(
        com.intellij.util.IconUtil.scale(AllIcons.Diff.Lock, null, 1.5f),
    ).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        preferredSize = Dimension(28, 28)
        toolTipText = DshBundle.message("config.permission")
        isVisible = false
    }

    private val bridgeListener = object : BridgeManager.Listener {
        override fun onState(state: BridgeState, capabilities: BridgeCapabilities?) {
            onEdt {
                updateTitle()
                updateStatus(service.state, null)
                if (state == BridgeState.ON) {
                    loadWelcomeSessions()
                    sessionId?.let { bridgeAttach(it) }
                } else {
                    presets = emptyList(); permModes = emptyList()
                    nativeCommands = emptyList(); skills = emptyList()
                    bridgeSubscribedSession = null // force resubscribe after a bridge reconnect
                    bridgeSubscribedClient = null
                    rebuildConfigBar()
                    updateSlashPopup()
                }
            }
        }

        override fun onEvent(event: JsonObject) {
            val kind = event.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
            if (kind != "session/event") return
            val ev = event.get("event") as? JsonObject ?: return
            val type = ev.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
            val sid = event.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
            if (sid != null && sid != sessionId && type != "session/title") return
            when (type) {
                "permission/preset", "sandbox/mode", "approval/policy" -> refreshPermissions()
                // preset switches re-scope the native command catalog
                "agent-preset/selected" -> { refreshPresets(); refreshNativeCommands() }
                "session/title" -> {
                    if (sid == sessionId) {
                        val t = (ev.get("data") as? JsonObject)
                            ?.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                        onEdt {
                            if (sid != sessionId) return@onEdt
                            currentTitle = t
                            updateTitle()
                        }
                    }
                }
            }
        }
    }

    private val stateListener = object : AcpService.StateListener {
        override fun onState(state: ConnectionState, detail: String?) {
            onEdt {
                updateStatus(state, detail)
                if (state == ConnectionState.READY) loadWelcomeSessions()
            }
        }
    }

    private var updateListener: AcpService.UpdateListener? = null
    private var sessionEpoch = 0L
    /** The latest explicit or startup request; older completions are ignored. */
    private var sessionRequest: Any? = null
        set(value) {
            field = value
            updateSendAvailability()
        }

    init {
        scroll.verticalScrollBar.model.addChangeListener {
            val m = scroll.verticalScrollBar.model
            // Content growth moves only the maximum; a changed value means the
            // user (or scrollToBottom) moved the view — re-evaluate following.
            if (m.value != lastScrollValue) {
                lastScrollValue = m.value
                autoScroll = m.value + m.extent >= m.maximum - JBUI.scale(32)
            }
        }

        composer.lineWrap = true
        composer.wrapStyleWord = true
        composer.emptyText.text = DshBundle.message("chat.composer.hint2")
        composer.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Pickers keep keyboard focus in the composer and get first
                // claim on navigation/selection keys while visible.
                if (filePopup.isVisible && filePopup.forwardKey(e)) {
                    e.consume()
                    return
                }
                if (slashPopup.isVisible && slashPopup.forwardKey(e)) {
                    e.consume()
                    return
                }
                // During IME composition (CJK input), the Enter that confirms a
                // candidate arrives with CHAR_UNDEFINED — never send on it.
                if (e.keyCode == KeyEvent.VK_ENTER && e.keyChar == '\n' &&
                    e.modifiersEx and InputEvent.SHIFT_DOWN_MASK == 0
                ) {
                    e.consume()
                    onSend()
                }
            }
        })
        // Same size as the transcript; CJK content swaps to a CJK UI face.
        composer.font = ChatFonts.body()
        composer.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private var cjk = false
            private fun swap() {
                val has = ChatFonts.hasCjk(composer.text)
                if (has != cjk) {
                    cjk = has
                    composer.font = if (has) ChatFonts.forText("中", ChatFonts.body()) else ChatFonts.body()
                }
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = swap()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = swap()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = swap()
        })
        composer.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updatePopups()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updatePopups()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updatePopups()
        })

        // One disc button: send when idle, stop while a turn runs.
        sendStopButton.addActionListener {
            if (busy) (busySessionId ?: sessionId)?.let(service::cancel) else onSend()
        }
        sendStopButton.toolTipText = DshBundle.message("chat.send")

        // One bordered box holds BOTH the input and the rail (dropdowns left,
        // permission lock + send/stop disc right) — the VS Code composer shape.
        configBar.isOpaque = false
        composer.border = JBUI.Borders.empty(2, 2, 0, 2)

        permissionButton.addActionListener { showPermissionPopup() }

        val rail = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0, 0, 0)
            // Bottom-align the selects with the lock icon and the send disc.
            val left = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(configBar, BorderLayout.SOUTH)
            }
            add(left, BorderLayout.WEST)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
            right.add(usageLabel)
            right.add(permissionButton)
            right.add(sendStopButton)
            add(right, BorderLayout.EAST)
        }

        val composerBox = JPanel(BorderLayout()).apply {
            border = javax.swing.BorderFactory.createCompoundBorder(
                com.intellij.ui.RoundedLineBorder(JBColor.border(), 10),
                JBUI.Borders.empty(4, 6),
            )
            add(JBScrollPane(composer).apply { border = null }, BorderLayout.CENTER)
            add(rail, BorderLayout.SOUTH)
        }
        // Composer hugs the window's bottom edge; the usage meter lives in the rail.
        val composerPanel = JPanel(BorderLayout()).apply {
            // Room for the rounded border's bottom arc, otherwise it clips.
            border = JBUI.Borders.empty(4, 4, 3, 4)
            add(composerBox, BorderLayout.CENTER)
        }

        val south = JPanel(BorderLayout())
        south.add(composerPanel, BorderLayout.CENTER)

        centerCards.add(welcomePanel, "welcome")
        centerCards.add(scroll, "chat")
        val unfocus = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                centerCards.requestFocusInWindow()
            }
        }
        centerCards.addMouseListener(unfocus)
        entriesPanel.addMouseListener(unfocus)
        (centerCards.layout as java.awt.CardLayout).show(centerCards, "welcome")

        add(centerCards, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        loadWelcomeSessions()

        service.addStateListener(stateListener)
        service.permissionResponder = { req -> PermissionBroker.ask(project, req) }
        bridge.addListener(bridgeListener)
        bridge.start()
        updateStatus(service.state, null)
        renderTimer.start()
        ensureAutoSession()
    }

    /* --------------------- title/gear action entry points --------------------- */

    fun createNewSessionAction() = createNewSession()
    fun openSessionsAction() = openSessions()
    fun showSkillsDialogAction() = showSkillsDialog()
    fun exportSessionAction() = exportSession()
    fun importWebConfigAction() = configSyncFlow.run()
    fun reconnectAction() = reconnect()
    fun runDoctorAction() = runDoctor()
    fun installBridgeAction() {
        BridgeInstall.run(
            project,
            bridge,
            onInstalled = { service.reset() },
        )
    }

    /* ---------------------------- bridge glue ---------------------------- */

    /** Subscribe to session events, attach the workspace, fetch presets/permissions. Idempotent per session. */
    private fun bridgeAttach(id: String) {
        if (!bridge.isOn) return
        val c = bridge.client ?: return
        val caps = bridge.capabilities
        // Serialize unsubscribe/subscribe pairs in the order session switches
        // were initiated. A slow A can finish before the queued B, but can no
        // longer overwrite B's later subscription.
        bridgeAttachExecutor.execute {
            if (disposed || sessionId != id || bridge.client !== c) return@execute
            if (bridgeSubscribedSession != id || bridgeSubscribedClient !== c) {
                runCatching { c.request("session.unsubscribe", timeoutMs = 5_000).get(5, java.util.concurrent.TimeUnit.SECONDS) }
                runCatching {
                    c.request("session.subscribe", JsonObject().apply { addProperty("sessionId", id) }, 5_000)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS)
                }.onSuccess {
                    bridgeSubscribedSession = id
                    bridgeSubscribedClient = c
                }
            }
            if (caps?.workspaceGrouping == true) {
                runCatching {
                    c.request("workspace.attach", JsonObject().apply { addProperty("sessionId", id) }, 5_000)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            if (caps?.presets == true) refreshPresets()
            if (caps?.permissions == true) refreshPermissions()
            if (caps?.commands == true) refreshNativeCommands()
            if (caps?.skills == true) refreshSkills()
        }
    }

    private fun refreshNativeCommands() {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = runCatching {
                c.request("command.list", JsonObject().apply { addProperty("sessionId", id) }, 10_000)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull() ?: return@executeOnPooledThread
            val parsed = res.getAsJsonArray("commands")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                if (name in SlashCommands.NATIVE_BLOCKLIST) return@mapNotNull null
                val zh = SlashCommands.NATIVE_ZH[name]
                SlashCommand(
                    name = name,
                    label = zh?.first ?: name,
                    description = o.get("description")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: zh?.second ?: "",
                    section = SlashCommand.Section.COMMAND,
                    run = SlashCommand.RunKind.NATIVE,
                    hint = o.get("inputHint")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            } ?: return@executeOnPooledThread
            onEdt {
                if (sessionId != id || bridge.client !== c) return@onEdt
                nativeCommands = parsed
                updateSlashPopup()
            }
        }
    }

    private fun refreshSkills(onDone: (() -> Unit)? = null) {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = runCatching {
                c.request("skill.list", JsonObject().apply { addProperty("sessionId", id) }, 10_000)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull() ?: return@executeOnPooledThread
            val parsed = res.getAsJsonArray("skills")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                SkillInfo(
                    name = name,
                    description = o.get("description")?.takeIf { it.isJsonPrimitive }?.asString,
                    whenToUse = o.get("whenToUse")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            } ?: return@executeOnPooledThread
            onEdt {
                if (sessionId != id || bridge.client !== c) return@onEdt
                skills = parsed
                updateSlashPopup()
                onDone?.invoke()
            }
        }
    }

    /* ------------------------- slash command flow ------------------------- */

    /** The full menu: local actions + native bridge commands + skills. */
    private fun buildSlashCommands(): List<SlashCommand> {
        val out = ArrayList<SlashCommand>()
        fun local(name: String, label: String, description: String) {
            out.add(SlashCommand(name, label, description, SlashCommand.Section.COMMAND, SlashCommand.RunKind.LOCAL))
        }
        local("new", DshBundle.message("cmd.new.label"), DshBundle.message("cmd.new.desc"))
        if (bridge.capabilities?.sessionExport == true) {
            local("export", DshBundle.message("cmd.export.label"), DshBundle.message("cmd.export.desc"))
        }
        if (permModes.isNotEmpty()) {
            local("permission", DshBundle.message("cmd.permission.label"), DshBundle.message("cmd.permission.desc"))
        }
        if (findModelOption(configOptions) != null) {
            local("model", DshBundle.message("cmd.model.label"), DshBundle.message("cmd.model.desc"))
        }
        if (findEffortOption(configOptions) != null) {
            local("effort", DshBundle.message("cmd.effort.label"), DshBundle.message("cmd.effort.desc"))
        }
        out.addAll(nativeCommands)
        skills.mapTo(out) {
            SlashCommand(
                it.name, it.name, it.description ?: it.whenToUse ?: "",
                SlashCommand.Section.SKILL, SlashCommand.RunKind.SKILL,
            )
        }
        return out
    }

    private fun updatePopups() {
        updateSlashPopup()
        updateFilePopup()
    }

    /** "@query" immediately before the caret opens the project file picker.
     *  insertUpdate fires BEFORE the caret moves, so read it a beat later. */
    private fun updateFilePopup() {
        onEdt {
            val caret = composer.caretPosition
            val before = composer.text.take(caret)
            val line = before.substringAfterLast('\n')
            val m = Regex("[@＠]([\\w.-]*)$").find(line)
            if (m == null) {
                filePopup.hide()
                return@onEdt
            }
            slashPopup.hide() // one picker at a time
            filePopup.update(m.groupValues[1])
        }
    }

    private fun onFilePick(pick: FileMentionPopup.FilePick) {
        // Replace the "@query" token with "@name " and remember the attachment.
        val caret = composer.caretPosition
        val before = composer.text.take(caret)
        val tokenStart = maxOf(before.lastIndexOf('@'), before.lastIndexOf('＠'))
        if (tokenStart < 0) return
        var name = pick.name
        var n = 2
        while (pendingFiles.containsKey(name)) name = pick.name + "-" + n++
        pendingFiles[name] = pick.absPath
        composer.text = composer.text.substring(0, tokenStart) + "@" + name + " " + composer.text.substring(caret)
        composer.caretPosition = tokenStart + name.length + 2
        composer.requestFocusInWindow()
    }

    private fun updateSlashPopup() {
        val text = composer.text
        // The picker only applies to a leading "/" line without newlines.
        if (!text.startsWith("/") || text.contains('\n')) {
            slashPopup.hide()
            return
        }
        val query = text.removePrefix("/")
        val matches = SlashCommands.filter(buildSlashCommands(), query)
        if (matches.isEmpty()) slashPopup.hide() else slashPopup.show(composer, matches)
    }

    private fun onSlashPick(cmd: SlashCommand) {
        when (cmd.run) {
            SlashCommand.RunKind.LOCAL -> {
                composer.text = ""
                executeLocal(cmd)
            }
            SlashCommand.RunKind.NATIVE ->
                if (cmd.name in SlashCommands.NATIVE_FILL || cmd.hint != null) {
                    composer.text = "/${cmd.name} "
                    composer.caretPosition = composer.text.length
                } else {
                    composer.text = ""
                    runNativeCommand("/${cmd.name}")
                }
            SlashCommand.RunKind.SKILL -> {
                composer.text = "/${cmd.name} "
                composer.caretPosition = composer.text.length
            }
        }
        slashPopup.hide()
    }

    private fun executeLocal(cmd: SlashCommand) {
        when (cmd.name) {
            "new" -> createNewSession()
            "export" -> exportSession()
            "permission" -> showPermissionPopup()
            "model" -> findModelOption(configOptions)?.let { railSelects[it.id]?.openPopup() }
            "effort" -> findEffortOption(configOptions)?.let { railSelects[it.id]?.openPopup() }
        }
    }

    /** Run a native dsh slash command through the bridge (command.run, bridge ≥ 0.1.3). */
    private fun runNativeCommand(line: String) {
        val c = bridge.client ?: run {
            addSystemMessage(DshBundle.message("cmd.needBridge"))
            return
        }
        // Pin the session id: the command may outlive a session switch (compact
        // runs up to its timeout), and its result must not leak into the next
        // session's transcript.
        val id = sessionId ?: run {
            addSystemMessage(DshBundle.message("cmd.needSession"))
            return
        }
        addSystemMessage(DshBundle.message("cmd.running", line))
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val res = c.request("command.run", JsonObject().apply {
                    addProperty("sessionId", id)
                    addProperty("line", line)
                }, 120_000).get(120, java.util.concurrent.TimeUnit.SECONDS)
                val kind = res?.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: "success"
                val text = res?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                onEdt {
                    if (sessionId != id) return@onEdt // switched away meanwhile
                    if (!text.isNullOrBlank()) {
                        if (kind == "error") addError(text) else addSystemMessage(text)
                    }
                }
            } catch (e: Exception) {
                val isTooOld = (e as? java.util.concurrent.ExecutionException)?.cause
                    ?.let { it is com.dshlite.idea.bridge.BridgeException && it.rpcCode == -32601 } == true
                onEdt {
                    if (sessionId != id) return@onEdt
                    addError(
                        if (isTooOld) DshBundle.message("cmd.bridgeTooOld")
                        else DshBundle.message("cmd.failed", rootMessage(e))
                    )
                }
            }
        }
    }

    /* ------------------------------ skills ------------------------------- */

    private fun showSkillsDialog(refreshed: Boolean = false) {
        if (!bridge.isOn || bridge.capabilities?.skills != true) {
            addSystemMessage(DshBundle.message("skills.needBridge"))
            return
        }
        // First open may race an empty cache: refresh once, then show (an
        // empty catalog used to re-trigger the refresh forever).
        if (skills.isEmpty() && !refreshed) {
            refreshSkills { showSkillsDialog(refreshed = true) }
            return
        }
        val dialog = SkillsDialog(project, skills)
        if (dialog.showAndGet()) {
            dialog.selected?.let {
                composer.text = "/${it.name} "
                composer.caretPosition = composer.text.length
                composer.requestFocusInWindow()
            }
        }
    }

    /* --------------------------- session export --------------------------- */

    private fun exportSession() {
        val c = bridge.client
        if (c == null || bridge.capabilities?.sessionExport != true) {
            addSystemMessage(DshBundle.message("export.needBridge"))
            return
        }
        val id = sessionId ?: run {
            addSystemMessage(DshBundle.message("cmd.needSession"))
            return
        }
        addSystemMessage(DshBundle.message("export.running"))
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val res = c.request("session.exportZip", JsonObject().apply { addProperty("sessionId", id) }, 120_000)
                    .get(120, java.util.concurrent.TimeUnit.SECONDS)
                val path = res?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
                if (path == null) {
                    onEdt { addError(DshBundle.message("export.failed", "no path in response")) }
                    return@executeOnPooledThread
                }
                val bytes = res.get("bytes")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0
                // The zip path is dsh-side; map it back to the IDE side for reveal/copy.
                val hostPath = com.dshlite.idea.launcher.createLauncher(
                    com.dshlite.idea.settings.DshSettingsState.getInstance()
                ).fromDshPath(path)
                val size = if (bytes > 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
                else "${maxOf(1, bytes / 1024)} KB"
                onEdt { showExportResult(hostPath, size) }
            } catch (e: Exception) {
                val isTooOld = (e as? java.util.concurrent.ExecutionException)?.cause
                    ?.let { it is com.dshlite.idea.bridge.BridgeException && it.rpcCode == -32601 } == true
                onEdt {
                    addError(
                        if (isTooOld) DshBundle.message("cmd.bridgeTooOld")
                        else DshBundle.message("export.failed", rootMessage(e))
                    )
                }
            }
        }
    }

    private fun showExportResult(hostPath: String, size: String) {
        val file = java.io.File(hostPath)
        val canReveal = file.exists() &&
            com.intellij.ide.actions.RevealFileAction.isSupported()
        val options = buildList {
            if (canReveal) add(DshBundle.message("export.reveal"))
            add(DshBundle.message("export.copyPath"))
        }.toTypedArray()
        val idx = Messages.showDialog(
            project,
            DshBundle.message("export.done", hostPath, size),
            DshBundle.message("chat.export"),
            options,
            0,
            Messages.getInformationIcon(),
        )
        if (idx < 0) return
        if (canReveal && idx == 0) {
            com.intellij.ide.actions.RevealFileAction.openFile(file)
        } else {
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                .setContents(java.awt.datatransfer.StringSelection(hostPath))
        }
    }

    private fun refreshPresets(retried: Boolean = false) {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = runCatching {
                c.request("preset.list", timeoutMs = 10_000).get(10, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull()
            val curResult = runCatching {
                c.request("preset.current", JsonObject().apply { addProperty("sessionId", id) }, 10_000)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            // The bridge learns a fresh session asynchronously; "not live" right
            // after session/new is a timing artifact — retry once after 2s.
            val curErr = curResult.exceptionOrNull()
            if (!retried && curErr != null &&
                (curErr.message ?: curErr.cause?.message ?: "").contains("not live")
            ) {
                Thread.sleep(2000)
                refreshPresets(retried = true)
                return@executeOnPooledThread
            }
            val cur = curResult.getOrNull()
            val parsed = list?.getAsJsonArray("presets")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val pid = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                PresetInfo(
                    id = pid,
                    name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: pid,
                    isDefault = o.get("isDefault")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    broken = effectivePresetBrokenReason(
                        id = pid,
                        hostSupportImported = cordisHostSupportImported,
                        nativeBroken = o.get("broken")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString,
                        failedPreviously = pid in failedPresets,
                    ),
                )
            } ?: return@executeOnPooledThread
            // First start reports null: fall back to the roster default ("standard").
            val current = cur?.get("preset")?.takeIf { !it.isJsonNull }?.asString
                ?: parsed.firstOrNull { it.isDefault }?.id
                ?: parsed.firstOrNull { it.id == "standard" }?.id
            onEdt {
                if (sessionId != id || bridge.client !== c) return@onEdt
                presets = parsed
                currentPreset = current
                rebuildConfigBar()
            }
        }
    }

    /** Lock-icon popup listing the permission modes, ✓ on the current one. */
    private fun showPermissionPopup() {
        if (permModes.isEmpty()) return
        val picker = PickerPopup<String>(
            anchorProvider = { permissionButton },
            onPick = { v -> if (v != currentPermMode) selectPermissionMode(v) },
            requestFocus = true,
            width = 220,
            alignRight = true,
        )
        picker.currentValue = currentPermMode
        picker.show(permModes.map {
            PickerPopup.Row(
                value = it.value,
                label = (if (it.value == currentPermMode) "✓ " else "    ") + it.name,
            )
        })
    }

    private fun refreshPermissions(retried: Boolean = false) {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val resResult = runCatching {
                c.request("permission.get", JsonObject().apply { addProperty("sessionId", id) }, 10_000)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            val resErr = resResult.exceptionOrNull()
            if (!retried && resErr != null &&
                (resErr.message ?: resErr.cause?.message ?: "").contains("not live")
            ) {
                Thread.sleep(2000)
                refreshPermissions(retried = true)
                return@executeOnPooledThread
            }
            val res = resResult.getOrNull() ?: return@executeOnPooledThread
            val options = res.getAsJsonArray("options")?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val v = o.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                PermissionMode(v, o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: v)
            } ?: return@executeOnPooledThread
            val current = res.get("current")?.takeIf { !it.isJsonNull }?.asString
            onEdt {
                if (sessionId != id || bridge.client !== c) return@onEdt
                permModes = options
                currentPermMode = current
                rebuildConfigBar()
            }
        }
    }

    private fun selectPreset(presetId: String) {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                c.request("preset.select", JsonObject().apply {
                    addProperty("sessionId", id)
                    addProperty("presetId", presetId)
                }, 10_000).get(10, java.util.concurrent.TimeUnit.SECONDS)
            }.onFailure { e ->
                // Mount-time failures are not flagged by the roster — learn them
                // (bridge forwards agent-preset/invalid as data.code since 0.1.3;
                // older hosts say "failed to mount").
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                val dataCode = (cause as? com.dshlite.idea.bridge.BridgeException)?.dataCode
                if (dataCode == "agent-preset/invalid" || (cause.message ?: "").contains("failed to mount")) {
                    failedPresets.add(presetId)
                    com.intellij.ide.util.PropertiesComponent.getInstance()
                        .setValue("dsh.brokenPresets", failedPresets.joinToString(","))
                }
                onEdt {
                    if (sessionId != id || bridge.client !== c) return@onEdt
                    addError(DshBundle.message("bridge.presetFailed", cause.message ?: ""))
                    refreshPresets()
                }
            }
        }
    }

    private fun selectPermissionMode(name: String) {
        val c = bridge.client ?: return
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val res = c.request("permission.set", JsonObject().apply {
                    addProperty("sessionId", id)
                    addProperty("name", name)
                }, 10_000).get(10, java.util.concurrent.TimeUnit.SECONDS)
                val current = res?.get("current")?.takeIf { !it.isJsonNull }?.asString
                onEdt {
                    if (sessionId != id || bridge.client !== c) return@onEdt
                    currentPermMode = current
                    rebuildConfigBar()
                }
            }.onFailure {
                onEdt {
                    if (sessionId == id) addError(DshBundle.message("bridge.permissionFailed", it.message ?: ""))
                }
            }
        }
    }

    /** Bridge-backed rename/delete for the sessions dialog; null when the bridge is off.
     *  The client is resolved AT CALL TIME — a reconnect swaps it, and a captured
     *  stale socket made every op fail with 'bridge connection closed'. */
    private fun bridgeSessionOps(): SessionOps? {
        val caps = bridge.capabilities ?: return null
        if (!caps.sessionTitle && !caps.sessionArchive) return null
        return SessionOps(
            canTitle = caps.sessionTitle,
            canDelete = caps.sessionArchive,
            rename = { id, title -> renameSession(id, title) },
            delete = { id -> deleteSession(id) },
        )
    }

    /**
     * Bridge session.setTitle only works on LIVE sessions; a stored one gets
     * resumed (activated in the host, no UI switch) first, then renamed.
     */
    private fun renameSession(id: String, title: String): java.util.concurrent.CompletableFuture<Void> {
        val f = java.util.concurrent.CompletableFuture<Void>()
        fun attempt(retried: Boolean = false) {
            val c = bridge.client
            if (c == null || !bridge.isOn) {
                f.completeExceptionally(com.dshlite.idea.bridge.BridgeException("bridge is not connected", -32000, null))
                return
            }
            c.request("session.setTitle", JsonObject().apply {
                addProperty("sessionId", id)
                addProperty("title", title)
            }, 15_000).whenComplete { _, err ->
                val cause = (err as? java.util.concurrent.ExecutionException)?.cause ?: err
                if (cause == null) {
                    onEdt {
                        if (id == sessionId) {
                            currentTitle = title
                            updateTitle()
                        }
                        loadWelcomeSessions()
                    }
                    f.complete(null)
                    return@whenComplete
                }
                if (!retried && (cause.message ?: "").contains("not live")) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        runCatching { service.resumeSession(id).get(20, java.util.concurrent.TimeUnit.SECONDS) }
                            .onSuccess { attempt(retried = true) }
                            .onFailure { f.completeExceptionally(it) }
                    }
                } else {
                    f.completeExceptionally(cause)
                }
            }
        }
        attempt()
        return f
    }

    /**
     * Delete = bridge archive (registry) + physical removal of the persisted
     * session data under $DSH_HOME/sessions (mirrors dsh-lite-vscode).
     */
    private fun deleteSession(id: String): java.util.concurrent.CompletableFuture<Void> {
        val f = java.util.concurrent.CompletableFuture<Void>()
        val c = bridge.client
        if (c == null || !bridge.isOn) {
            f.completeExceptionally(com.dshlite.idea.bridge.BridgeException("bridge is not connected", -32000, null))
            return f
        }
        c.request("session.delete", JsonObject().apply { addProperty("sessionId", id) }, 15_000).whenComplete { _, err ->
            val cause = (err as? java.util.concurrent.ExecutionException)?.cause ?: err
            if (cause is com.dshlite.idea.bridge.BridgeException && cause.dataCode == "session/active") {
                f.completeExceptionally(com.dshlite.idea.bridge.BridgeException(
                    DshBundle.message("sessions.delete.active"), -32009, "session/active"))
                return@whenComplete
            }
            if (cause != null) {
                f.completeExceptionally(cause)
                return@whenComplete
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                // Archive is registry-level; physically drop the persisted data.
                // The id becomes a `find -name` pattern feeding `rm -rf`: an id
                // like "*" would match every session, so only inert ids pass.
                if (com.dshlite.idea.chat.SessionIds.isSafe(id)) {
                    val script = "root=\"${'$'}{DSH_HOME:-${'$'}HOME/.dsh}/sessions\"; " +
                        "[ -d \"${'$'}root\" ] || exit 0; " +
                        "find \"${'$'}root\" -mindepth 2 -maxdepth 2 -type d \\( -name 'session-" + id + "' -o -name '" + id + "' \\) " +
                        "-exec rm -rf {} +"
                    log.info("[dsh] deleteSession: removing persisted data of " + id)
                    runCatching { service.runTargetShell(script, 15_000).get() }
                } else {
                    log.warn("[dsh] deleteSession: unsafe session id, persisted data kept: " + id)
                }
                onEdt {
                    if (sessionId == id) {
                        runCatching { service.closeSession(id) }
                        // Detach too: a stale listener would route a late
                        // update for the deleted session into the next one.
                        updateListener?.let(service::removeUpdateListener)
                        updateListener = null
                        sessionEpoch++
                        sessionId = null
                        currentTitle = null
                        clearEntries()
                        updateTitle()
                    }
                }
                markArchived(id)
                f.complete(null)
            }
        }
        return f
    }

    /* --------------------------- session flow ---------------------------- */

    private fun updateStatus(state: ConnectionState, detail: String?) {
        updateTitle()
    }

    private fun createNewSession() {
        if (busy) return
        // A blank session is reusable: don't stack empty sessions.
        if (sessionId != null && entries.isEmpty()) {
            composer.requestFocusInWindow()
            return
        }
        val request = Any().also { sessionRequest = it }
        addSystemMessage(DshBundle.message("chat.creatingSession"))
        service.newSession().whenComplete { result, error ->
            onEdt {
                if (sessionRequest !== request) return@onEdt
                sessionRequest = null
                if (error != null) {
                    addError(DshBundle.message("chat.sessionFailed", rootMessage(error)))
                    return@onEdt
                }
                switchSession(result.sessionId)
                markSessionUsed(result.sessionId)
                markSessionClean()
                applyConfigOptions(result.configOptions)
                applyRememberedSelection()
            }
        }
    }

    /** Register the update listener for newId. Thread-safe: registration is a
     *  CopyOnWriteArrayList add, and the listener itself marshals to the EDT.
     *  Must run BEFORE any request whose response triggers replayed updates. */
    private fun attachSession(newId: String) {
        updateListener?.let(service::removeUpdateListener)
        sessionId = newId
        val epoch = ++sessionEpoch
        val listener = object : AcpService.UpdateListener {
            override fun onUpdate(update: SessionUpdateData) {
                onEdt {
                    if (sessionEpoch == epoch && sessionId == newId) applyUpdate(update)
                }
            }
        }
        updateListener = listener
        service.addUpdateListener(newId, listener)
    }

    /** EDT: attach + reset the transcript UI + bridge glue.
     *  A resume is a conversation; a fresh empty session keeps the landing page. */
    private fun switchSession(newId: String, title: String? = null) {
        attachSession(newId)
        currentTitle = title
        configOptions = emptyList()
        presets = emptyList()
        permModes = emptyList()
        nativeCommands = emptyList()
        skills = emptyList()
        currentPreset = null
        currentPermMode = null
        rebuildConfigBar()
        updateSlashPopup()
        usageLabel.text = " "
        clearEntries()
        updateTitle()
        bridgeAttach(newId)
        log.info("[dsh] session selected: " + newId.take(8))
    }

    /** Title of the current session (bridge-backed), null until known. */
    private var currentTitle: String? = null

    /** Title bar: "<status dot> <session name> (<id8>)". Dot: green = acp+bridge,
     *  yellow = acp only, red = neither (mirrors the VS Code header dot). */
    private fun updateTitle() {
        val acp = service.isReady
        val br = bridge.isOn
        val dot = if (acp && br) "🟢" else if (acp) "🟡" else "🔴"
        val id = sessionId
        if (id == null) {
            titleLabel.text = dot
            return
        }
        val name = currentTitle?.takeIf { it.isNotBlank() } ?: DshBundle.message("sessions.untitled")
        titleLabel.text = "$dot $name (${id.take(8)})"
    }

    /** Landing iff the transcript is empty and no turn is running. */
    private fun syncCards() {
        val show = entries.isEmpty() && !busy
        if (show == showingWelcome) return
        showingWelcome = show
        (centerCards.layout as java.awt.CardLayout).show(centerCards, if (show) "welcome" else "chat")
        if (show) loadWelcomeSessions()
    }

    /**
     * This host's session/resume does not replay transcript updates (verified
     * against dsh 0.2.0), so the history comes from the durable session log
     * (port of dsh-lite-vscode sessionHistory.ts): the bridge's
     * `session.exportZip` when available, else a target-side read of
     * $DSH_HOME/sessions/<ws>/<id>/session*.jsonl.zstd decoded by node.
     */
    private fun loadTranscript(id: String) {
        val epoch = sessionEpoch
        val client = bridge.client?.takeIf { bridge.isOn && bridge.capabilities?.sessionExport == true }
        val launcher = com.dshlite.idea.launcher.createLauncher(com.dshlite.idea.settings.DshSettingsState.getInstance())
        ApplicationManager.getApplication().executeOnPooledThread {
            var failure: Throwable? = null
            var source = "bridge"
            var messages: List<com.dshlite.idea.chat.HistoryMessage>? = null
            if (client != null) {
                try {
                    messages = com.dshlite.idea.chat.SessionHistory.importViaBridge(client, launcher, id)
                } catch (e: Throwable) {
                    failure = e
                }
            }
            if (messages == null) {
                source = "shell"
                val script = com.dshlite.idea.chat.SessionHistory.shellScript(id)
                if (script != null) {
                    try {
                        val out = service.runTargetShell(script, 30_000).get(35, java.util.concurrent.TimeUnit.SECONDS)
                        val parsed = com.dshlite.idea.chat.SessionHistory.parse(out)
                        messages = parsed
                        failure = null
                    } catch (e: Throwable) {
                        if (failure == null) failure = e
                    }
                }
            }
            if (messages != null) {
                log.info("[dsh] history loaded: session=" + id.take(8) + " messages=" + messages.size + " source=" + source)
            } else {
                log.warn("[dsh] history load failed: session=" + id.take(8) + ": " + (failure?.let(::rootMessage) ?: "no readable log"))
            }
            onEdt {
                if (sessionId != id || sessionEpoch != epoch) return@onEdt
                removeHistoryPlaceholder()
                val list = messages
                if (list.isNullOrEmpty()) {
                    // Only a user-initiated resume explains itself; the landing
                    // page stays for an empty session.
                    if (failure != null) addSystemMessage(DshBundle.message("chat.historyFailed", rootMessage(failure!!)))
                    syncCards()
                    return@onEdt
                }
                renderHistory(list)
            }
        }
    }

    /** Prepend the imported transcript (anything already streamed stays below). */
    private fun renderHistory(list: List<com.dshlite.idea.chat.HistoryMessage>) {
        var at = 0
        fun put(e: Entry) {
            entries.add(at, e)
            entriesPanel.add(e.component, at)
            at++
        }
        for (m in list) {
            when (m) {
                is com.dshlite.idea.chat.HistoryMessage.User -> put(Entry(UserMessageRow(m.text)))
                is com.dshlite.idea.chat.HistoryMessage.Assistant ->
                    put(Entry(AssistantMessageRow().apply { setMarkdown(m.text) }))
                is com.dshlite.idea.chat.HistoryMessage.Thought ->
                    put(Entry(ThoughtCard().apply { setText(m.text) }))
                is com.dshlite.idea.chat.HistoryMessage.Tool -> {
                    val card = ToolCard(
                        ToolCard.ToolState(
                            title = m.title, subtitle = m.subtitle, status = m.status,
                            input = m.input, detail = m.detail,
                        )
                    )
                    put(ToolEntry(card))
                }
            }
        }
        entriesPanel.revalidate()
        entriesPanel.repaint()
        syncCards()
        scrollToBottom(force = true)
    }

    private fun removeHistoryPlaceholder() {
        val p = historyPlaceholder ?: return
        historyPlaceholder = null
        entries.remove(p)
        entriesPanel.remove(p.component)
        entriesPanel.revalidate()
        entriesPanel.repaint()
    }

    /** The last session this panel used, and whether it ever carried content. */
    private fun props() = com.intellij.ide.util.PropertiesComponent.getInstance(project)

    private fun markSessionUsed(id: String) {
        props().setValue("dsh.lastSessionId", id)
    }

    private fun markSessionClean() {
        // NOTE: PropertiesComponent omits false from storage — never store
        // a "clean=false" state; removal IS the dirty marker.
        props().setValue("dsh.lastSessionClean", true)
    }

    private fun markSessionDirty(id: String) {
        if (props().getValue("dsh.lastSessionId") == id &&
            props().getBoolean("dsh.lastSessionClean", false)
        ) {
            props().setValue("dsh.lastSessionClean", false) // removes the property
        }
    }

    /**
     * Boot the agent and create a session in the background so the composer rail
     * (preset/model/effort) is already populated on the landing screen — the
     * welcome card stays up until the first prompt or a resume.
     */
    private fun ensureAutoSession() {
        if (sessionId != null || busy) return
        // VS Code parity: an untouched empty session is reused instead of
        // stacking a fresh empty one on every launch.
        val lastId = props().getValue("dsh.lastSessionId")
        val clean = props().getBoolean("dsh.lastSessionClean", false)
        if (lastId != null && clean) {
            resume(lastId, quiet = true)
            return
        }
        val request = Any().also { sessionRequest = it }
        service.newSession().whenComplete { result, err ->
            onEdt {
                if (sessionRequest !== request) return@onEdt
                sessionRequest = null
                if (err != null || sessionId != null || busy) return@onEdt
                attachSession(result.sessionId)
                currentTitle = null
                bridgeAttach(result.sessionId)
                markSessionUsed(result.sessionId)
                markSessionClean()
                updateTitle()
                applyConfigOptions(result.configOptions)
                applyRememberedSelection()
            }
        }
    }

    /** Fill the welcome panel's recent-session list (best effort, agent boots for this). */
    private fun loadWelcomeSessions() {
        if (!showingWelcome) return
        fetchSessions().whenComplete { list, err ->
            onEdt {
                if (err == null && showingWelcome) welcomePanel.setSessions(list)
            }
        }
    }

    private fun reconnect() {
        service.reset()
        addSystemMessage(DshBundle.message("chat.reconnecting"))
        service.ensureClient()
    }

    /** Locally archived session ids (deleted from this client); kept out of every list. */
    private fun archivedLocal(): MutableSet<String> =
        (com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .getValue("dsh.archivedSessionIds") ?: "")
            .split(',').filter { it.isNotBlank() }.toMutableSet()

    private fun markArchived(id: String) {
        val set = archivedLocal()
        set.add(id)
        com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .setValue("dsh.archivedSessionIds", set.joinToString(","))
    }

    /** Bridge titles + stored rows when connected; otherwise ACP (all pages). */
    private fun fetchSessions(): java.util.concurrent.CompletableFuture<List<com.dshlite.idea.acp.SessionInfoData>> {
        val archived = archivedLocal()
        val c = bridge.client?.takeIf { bridge.isOn }
        if (c == null) return service.listSessions().thenApply { result ->
            result.first.filter { it.sessionId !in archived }
        }
        return com.dshlite.idea.chat.SessionCatalog(
            request = c::request,
            workspace = service.workspaceDshCwd(),
            ignoreCase = com.intellij.openapi.util.SystemInfoRt.isWindows &&
                com.dshlite.idea.settings.DshSettingsState.getInstance().runtime in setOf("windows", "local"),
        ).fetch(archived)
    }

    private fun openSessions() {
        fetchSessions().whenComplete { list, error ->
            onEdt {
                if (error != null) {
                    addError(DshBundle.message("chat.listFailed", rootMessage(error)))
                    return@onEdt
                }
                if (list.isEmpty()) {
                    addSystemMessage(DshBundle.message("chat.noSessions"))
                    return@onEdt
                }
                val dialog = SessionsDialog(project, list, bridgeSessionOps(), currentId = sessionId) { onChanged ->
                    // Rename/delete landed: refresh the welcome list now, and
                    // re-list for the dialog after a beat (the host applies the
                    // archive asynchronously).
                    loadWelcomeSessions()
                    fetchSessions().whenComplete { fresh, err ->
                        onEdt { if (err == null && fresh != null) onChanged(fresh) }
                    }
                    Timer(800) {
                        if (!disposed) {
                            fetchSessions().whenComplete { fresh, err ->
                                onEdt { if (err == null && fresh != null) onChanged(fresh) }
                            }
                        }
                    }.apply { isRepeats = false; start() }
                }
                if (dialog.showAndGet()) {
                    val selected = dialog.selected ?: return@onEdt
                    resume(selected.sessionId, title = selected.title)
                }
            }
        }
    }

    /**
     * Switch to a stored session and rebuild its transcript.
     * [quiet]: the startup reuse of an untouched blank session — no history
     * to fetch, and the landing page stays up.
     */
    private fun resume(id: String, quiet: Boolean = false, title: String? = null) {
        if (id == sessionId) return
        val request = Any().also { sessionRequest = it }
        // ACP does not replay history. Keep the current transcript intact
        // until resume succeeds, as in the VS Code client; a failed switch
        // must not leave the composer targeting a session we do not own.
        service.resumeSession(id).whenComplete { options, error ->
            onEdt {
                if (sessionRequest !== request) return@onEdt
                sessionRequest = null
                if (error != null && !rootMessage(error).contains("already active")) {
                    addError(DshBundle.message("chat.resumeFailed", rootMessage(error)))
                    return@onEdt
                }
                switchSession(id, title)
                markSessionUsed(id)
                // A previous blank session's clean flag must never transfer
                // to an imported nonempty session on the next IDE startup.
                if (!quiet) markSessionDirty(id)
                if (!quiet) {
                    historyPlaceholder = Entry(SystemMessageRow(DshBundle.message("chat.historyLoading"))).also(::addEntry)
                    loadTranscript(id)
                }
                if (options != null) {
                    // Resumed sessions keep their own model/effort. Remembered
                    // selections are only defaults for freshly created sessions.
                    applyConfigOptions(options)
                } else {
                    refreshPresets()
                    refreshPermissions()
                }
            }
        }
    }

    private fun runDoctor() {
        service.doctor().whenComplete { out, error ->
            onEdt {
                if (error != null) {
                    Messages.showErrorDialog(project, rootMessage(error), DshBundle.message("chat.doctor"))
                } else {
                    val bridgeLine = if (bridge.isOn) {
                        DshBundle.message(
                            "doctor.bridge.on",
                            bridge.client?.handshake?.version ?: "?",
                            bridge.hostDshVersion ?: "unknown",
                        )
                    } else {
                        DshBundle.message("doctor.bridge.off") + "\n" +
                            DshBundle.message("doctor.bridge.discovery", bridge.lastDiscoveryInfo)
                    }
                    Messages.showInfoMessage(project, "$out\n$bridgeLine", DshBundle.message("chat.doctor"))
                }
            }
        }
    }

    /* ----------------------------- sending ------------------------------- */

    private fun onSend() {
        if (busy || sessionRequest != null || historyPlaceholder != null) return
        val text = composer.text.trim()
        if (text.isEmpty()) return
        // A leading "/name [args]" naming a known command is executed, not sent
        // to the model — same as picking it from the slash menu.
        when (val slash = SlashCommands.resolve(buildSlashCommands(), text)) {
            is SlashLine.Native -> {
                composer.text = ""
                runNativeCommand(slash.line)
                return
            }
            is SlashLine.Local -> {
                composer.text = ""
                executeLocal(slash.cmd)
                return
            }
            is SlashLine.Skill -> {
                // Skills are invoked through the model: ask it to use the skill.
                composer.text = ""
                sendPrompt("请使用 ${slash.name} 技能${if (slash.rest.isNotEmpty()) "：${slash.rest}" else ""}", draftText = text)
                return
            }
            null -> {}
        }
        composer.text = ""
        sendPrompt(text)
    }

    private fun sendPrompt(text: String, draftText: String = text) {
        var turnSessionId: String? = null
        var userMessageAdded = false
        val files = pendingFiles.toMap()
        pendingFiles.clear()

        val sid = sessionId
        val creationRequest = if (sid == null) Any().also { sessionRequest = it } else null
        setBusy(true)
        val promptFuture = if (sid != null) {
            java.util.concurrent.CompletableFuture.completedFuture(sid)
        } else {
            service.newSession().thenCompose { result ->
                val ready = java.util.concurrent.CompletableFuture<String>()
                onEdt {
                    if (sessionRequest !== creationRequest) {
                        // A newer explicit new/resume owns the UI now. Do not clear
                        // its transcript or steal its target; discard only the
                        // superseded empty session created for this send.
                        service.closeSession(result.sessionId)
                        ready.completeExceptionally(
                            java.util.concurrent.CancellationException("session creation was superseded")
                        )
                        return@onEdt
                    }
                    sessionRequest = null
                    switchSession(result.sessionId)
                    markSessionUsed(result.sessionId)
                    markSessionClean()
                    applyConfigOptions(result.configOptions)
                    applyRememberedSelection()
                    ready.complete(result.sessionId)
                }
                ready
            }
        }
        promptFuture
            // The user message must be appended AFTER any implicit session
            // creation: switchSession clears the entry list for the new session
            // and would wipe a message added up-front. Posted to the EDT and
            // chained so the prompt cannot race ahead of it.
            .thenCompose { id ->
                val f = java.util.concurrent.CompletableFuture<String>()
                onEdt {
                    turnSessionId = id
                    busySessionId = id
                    userMessageAdded = true
                    markSessionDirty(id)
                    addUserMessage(text)
                    f.complete(id)
                }
                f
            }
            .thenCompose { id ->
                busySessionId = id
                val blocks = ArrayList<ContentBlock>()
                // Referenced @-files cross to the dsh side as resource links.
                val launcher = com.dshlite.idea.launcher.createLauncher(
                    com.dshlite.idea.settings.DshSettingsState.getInstance()
                )
                val referencedNames = referencedMentionNames(text, files.keys)
                for ((name, path) in files) {
                    if (name in referencedNames) {
                        blocks.add(ContentBlock.resourceLink(launcher.toDshResourceUri(path), name))
                    }
                }
                blocks.add(ContentBlock.text(text))
                service.prompt(id, blocks)
            }
            .whenComplete { _, error ->
                onEdt {
                    if (sessionRequest === creationRequest) sessionRequest = null
                    setBusy(false)
                    val cancelled = isCancellation(error)
                    if (error != null && !userMessageAdded && composer.text.isBlank()) {
                        composer.text = draftText
                        composer.caretPosition = composer.text.length
                        files.forEach { (name, path) -> pendingFiles.putIfAbsent(name, path) }
                    }
                    if (!cancelled && (sessionId == turnSessionId || turnSessionId == null)) {
                        endStreaming()
                        if (error != null) addError(DshBundle.message("chat.promptFailed", rootMessage(error)))
                    }
                }
            }
    }

    private fun updateSendAvailability() {
        sendStopButton.isEnabled = busy || (sessionRequest == null && historyPlaceholder == null)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        updateSendAvailability()
        if (!b) busySessionId = null
        sendStopButton.glyph = if (b) RoundButton.Glyph.STOP else RoundButton.Glyph.SEND
        sendStopButton.toolTipText = DshBundle.message(if (b) "chat.cancel" else "chat.send")
        syncCards()
    }

    /* ------------------------- update application ------------------------- */

    private fun applyUpdate(u: SessionUpdateData) {
        // Only real conversation content makes a session "dirty" for the
        // blank-session reuse rule — config/usage updates don't count.
        if (u.kind in setOf("agent_message_chunk", "agent_thought_chunk", "user_message_chunk", "tool_call")) {
            sessionId?.let { markSessionDirty(it) }
        }
        when (u.kind) {
            "agent_message_chunk" -> appendChunk("assistant", u.text)
            "agent_thought_chunk" -> appendChunk("thought", u.text)
            "user_message_chunk" -> appendChunk("user", u.text)
            "tool_call" -> {
                endStreaming()
                addToolCall(u)
            }
            "tool_call_update" -> updateToolCall(u)
            "plan" -> updatePlan(u)
            "usage_update" -> updateUsage(u)
            "config_option_update" -> u.configOptions?.let { applyConfigOptions(it) }
        }
    }

    private fun appendChunk(kind: String, text: String?) {
        if (text.isNullOrEmpty()) return
        val current = streamingEntry
        if (current == null || streamingKind != kind) {
            endStreaming()
            streamingEntry = when (kind) {
                "assistant" -> addAssistantEntry()
                "thought" -> addThoughtEntry()
                else -> addUserStreamingEntry()
            }
            streamingKind = kind
        }
        streamingEntry?.let {
            it.buffer.append(text)
            it.dirty = true
        }
    }

    private fun endStreaming() {
        flushDirty()
        streamingEntry = null
        streamingKind = null
    }

    private fun flushDirty() {
        var flushed = false
        for (e in entries) {
            if (e is TextEntry && e.dirty) {
                e.dirty = false
                e.render(e.buffer.toString())
                flushed = true
            }
        }
        if (flushed) {
            entriesPanel.revalidate()
            entriesPanel.repaint()
            scrollToBottom()
        }
    }

    private fun addToolCall(u: SessionUpdateData) {
        val id = u.toolCallId ?: return
        toolEntries[id]?.let {
            // A repeated tool_call for a known id is a full refresh.
            updateToolCall(u)
            return
        }
        val card = ToolCard(
            ToolCard.ToolState(
                title = u.title ?: u.toolKind ?: "tool",
                subtitle = com.dshlite.idea.chat.ToolText.subtitle(u.rawInput),
                kind = u.toolKind,
                status = u.status ?: "pending",
                input = u.rawInput,
                output = u.rawOutput,
                detail = com.dshlite.idea.chat.ToolText.detail(u.content),
            )
        )
        val entry = ToolEntry(card)
        toolEntries[id] = entry
        addEntry(entry)
    }

    private fun updateToolCall(u: SessionUpdateData) {
        val entry = u.toolCallId?.let { toolEntries[it] } ?: return
        // tool_call_update carries only changed fields — merge.
        entry.card.update {
            u.title?.let { title = it }
            u.toolKind?.let { kind = it }
            u.status?.let { status = it }
            if (u.rawInput != null) {
                input = u.rawInput
                com.dshlite.idea.chat.ToolText.subtitle(u.rawInput)?.let { subtitle = it }
            }
            if (u.rawOutput != null) output = u.rawOutput
            com.dshlite.idea.chat.ToolText.detail(u.content)?.let { detail = it }
        }
        scrollToBottom()
    }

    private fun updatePlan(u: SessionUpdateData) {
        endStreaming()
        val items = u.planEntries ?: return
        val card = planCard ?: TodoCard().also {
            planCard = it
            addEntry(Entry(it))
        }
        card.setEntries(items.map { it.content to it.status })
        scrollToBottom()
    }

    private fun updateUsage(u: SessionUpdateData) {
        val used = u.used ?: return
        val size = u.contextSize ?: return
        usageLabel.text = DshBundle.message("status.usage", formatTokens(used), formatTokens(size))
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }

    /* --------------------- model/effort selection logic --------------------- */

    /**
     * Central session config setter: surfaces errors in the transcript,
     * remembers the model+effort pick, and repins the effort after a model
     * switch (a model change resets the effort to provider-default).
     */
    private fun selectConfigOption(configId: String, value: String, skipEffortDefault: Boolean = false, onDone: (() -> Unit)? = null) {
        val sid = sessionId ?: return
        service.setConfigOption(sid, configId, value).whenComplete { newOptions, err ->
            onEdt {
                if (sessionId != sid) return@onEdt
                try {
                    if (err != null) {
                        addError(DshBundle.message("config.failed", rootMessage(err)))
                        return@onEdt
                    }
                    if (newOptions.isNotEmpty()) {
                        applyConfigOptions(newOptions)
                        rememberSelection()
                        val modelId = findModelOption(newOptions)?.id ?: "model"
                        if (!skipEffortDefault && configId == modelId) {
                            pinEffortAfterModelSwitch()
                        }
                    }
                } finally {
                    onDone?.invoke()
                }
            }
        }
    }

    /** model id → defaultEffort, target-side: the profile's cordis.patch.yml layered
     *  over the legacy global settings.yaml (mirrors dsh-lite-vscode). */
    private fun loadEffortDefaults(retried: Boolean = false, onDone: (Map<String, String>) -> Unit) {
        effortDefaults?.let { onDone(it); return }
        val profile = com.dshlite.idea.settings.DshSettingsState.getInstance().profile
        val profileDir = com.dshlite.idea.launcher.ShellEnv.shellQuote(profile)
        val script = "H=\"${'$'}{DSH_HOME:-${'$'}HOME/.dsh}\"; cat \"${'$'}H/settings.yaml\" 2>/dev/null; " +
            "printf '\\001'; " +
            "cat \"${'$'}H/profiles/\"" + profileDir + "\"/cordis.patch.yml\" 2>/dev/null" +
            com.dshlite.idea.launcher.ShellEnv.SHORT_CMD_SETTLE
        service.runTargetShell(script, 10_000).whenComplete { out, err ->
            try {
                handleEffortDefaults(out, err, retried, onDone)
            } catch (t: Throwable) {
                // Errors (e.g. NoClassDefFoundError) must surface, not vanish
                // into the discarded whenComplete future.
                log.warn("[dsh] effort defaults read failed", t)
                onDone(emptyMap())
            }
        }
    }

    private fun handleEffortDefaults(
        outNullable: String?,
        err: Throwable?,
        retried: Boolean,
        onDone: (Map<String, String>) -> Unit,
    ) {
        val out = outNullable ?: ""
        val map = HashMap<String, String>()
        if (err == null && out.isNotEmpty()) {
            val yaml = org.yaml.snakeyaml.Yaml()
            for (doc in out.split('\u0001')) {
                // cordis.patch.yml embeds `!!js <expr>` values that snakeyaml
                // cannot tag-resolve; they are irrelevant to defaultEffort, so
                // blank them to a scalar before parsing.
                val stripped = doc.replace(Regex("!!js" + "\\s+[^\\n]*"), "false")
                runCatching { collectEffortDefaults(yaml.load<Any>(stripped), map) }
            }
        }
        if (map.isEmpty() && !retried) {
            // Short-lived wsl.exe commands intermittently return empty output on
            // some machines — do not cache a miss; retry once after a beat.
            Thread.sleep(500)
            loadEffortDefaults(retried = true, onDone = onDone)
            return
        }
        if (map.isNotEmpty()) effortDefaults = map
        onDone(map)
    }

    /**
     * After a model switch the host resets the effort (to provider-default or its
     * own deployment default): always pin the new model's configured defaultEffort
     * when known, else the highest level when sitting at provider-default.
     */
    private fun pinEffortAfterModelSwitch() {
        val sid = sessionId
        val options = configOptions
        val effort = findEffortOption(options) ?: return
        if (effort.type != "select") return
        val modelId = findModelOption(options)?.let { modelIdOf(it.currentValue) }
        loadEffortDefaults { defaults ->
            onEdt {
                if (sessionId != sid || configOptions != options) return@onEdt
                val configured = modelId?.let { defaults[it] }
                val desired = when {
                    configured != null && effort.options.any { it.value == configured } -> configured
                    effort.currentValue == PROVIDER_DEFAULT_EFFORT_VALUE -> chooseEffort(effort.options, null)
                    else -> null // keep the user's explicit non-default pick
                }
                if (desired != null && desired != effort.currentValue) {
                    selectConfigOption(effort.id, desired, skipEffortDefault = true)
                }
            }
        }
    }

    /** If the effort selector sits at provider-default, pin the configured/highest level. */
    private fun pinEffortIfProviderDefault() {
        val sid = sessionId
        val options = configOptions
        val effort = findEffortOption(options) ?: return
        if (effort.type != "select" || effort.currentValue != PROVIDER_DEFAULT_EFFORT_VALUE) return
        val modelId = findModelOption(options)?.let { modelIdOf(it.currentValue) }
        loadEffortDefaults { defaults ->
            onEdt {
                if (sessionId != sid || configOptions != options) return@onEdt
                val desired = chooseEffort(effort.options, modelId?.let { defaults[it] }) ?: return@onEdt
                selectConfigOption(effort.id, desired, skipEffortDefault = true)
            }
        }
    }

    /** Persist the current model+effort pick for future fresh sessions. */
    private fun rememberSelection() {
        val options = configOptions
        val model = findModelOption(options)?.takeIf { it.currentValue.isNotEmpty() } ?: return
        val effort = findEffortOption(options)?.takeIf { it.currentValue != PROVIDER_DEFAULT_EFFORT_VALUE }
        com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .setValue("dsh.lastModelSelection", model.currentValue + "\n" + (effort?.currentValue ?: ""))
    }

    /** Replay the remembered model+effort onto a freshly created session. */
    private fun applyRememberedSelection() {
        val remembered = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .getValue("dsh.lastModelSelection")?.split('\n') ?: emptyList()
        val rememberedModel = remembered.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val rememberedEffort = remembered.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val modelOpt = findModelOption(configOptions)
        // No remembered model: the server default may reference a deactivated
        // deployment (raw wire has no marker). Prefer the first entry with a
        // curated display name (name differs from the raw model id), else the
        // plain first entry.
        val defaultModel = modelOpt?.options?.firstOrNull { o ->
            val modelId = com.dshlite.idea.acp.modelIdOf(o.value)
            modelId != null && o.name != modelId
        }?.value ?: modelOpt?.options?.firstOrNull()?.value
        val desiredModel = rememberedModel
            ?.takeIf { modelOpt?.options?.any { o -> o.value == it } == true }
            ?: defaultModel
        if (modelOpt != null && desiredModel != null && modelOpt.currentValue != desiredModel) {
            selectConfigOption(modelOpt.id, desiredModel, skipEffortDefault = true) { applyRememberedEffort(rememberedEffort) }
        } else {
            applyRememberedEffort(rememberedEffort)
        }
    }

    private fun applyRememberedEffort(rememberedEffort: String?) {
        val effortOpt = findEffortOption(configOptions) ?: return
        if (rememberedEffort != null && effortOpt.currentValue != rememberedEffort &&
            effortOpt.options.any { it.value == rememberedEffort }
        ) {
            selectConfigOption(effortOpt.id, rememberedEffort, skipEffortDefault = true)
        } else {
            pinEffortIfProviderDefault()
        }
    }

    /* ------------------------- config options bar ------------------------- */

    private fun applyConfigOptions(options: List<ConfigOption>) {
        configOptions = options
        rebuildConfigBar()
    }

    private fun rebuildConfigBar() {
        updatingConfigBar = true
        try {
            configBar.removeAll()
            railSelects = emptyMap()
            configBar.isVisible = false
            permissionButton.isVisible = permModes.isNotEmpty()

            // Rail order: preset → model → reasoning effort (…then any extras).
            if (presets.isNotEmpty()) configBar.add(buildPresetSelect())
            val modelOpt = findModelOption(configOptions)
            if (modelOpt != null) configBar.add(buildOptionSelect(modelOpt))
            val effortOpt = findEffortOption(configOptions)
            if (effortOpt != null) configBar.add(buildOptionSelect(effortOpt))
            for (opt in configOptions) {
                if (opt === modelOpt || opt === effortOpt) continue
                configBar.add(buildOptionSelect(opt))
            }

            configBar.isVisible = configBar.componentCount > 0
            configBar.revalidate()
            configBar.repaint()
        } finally {
            updatingConfigBar = false
        }
    }

    private fun buildPresetSelect(): RailSelect {
        val sel = RailSelect(DshBundle.message("config.preset"))
        sel.items = presets.map { p ->
            RailSelect.Item(
                value = p.id,
                label = p.name,
                // Unavailable presets: disabled menu rows with the reason as tooltip.
                enabled = p.broken == null,
                hint = p.broken,
            )
        }
        sel.selectedValue = currentPreset
        sel.onSelect = { if (!updatingConfigBar) selectPreset(it) }
        return sel
    }

    private fun buildOptionSelect(opt: ConfigOption): RailSelect {
        val isEffort = opt === findEffortOption(configOptions)
        val displayOptions = if (isEffort) {
            // "Provider default" is not a real choice: hide it; the pin logic
            // moves the session to the configured/highest effort instead.
            opt.options.filter { it.value != PROVIDER_DEFAULT_EFFORT_VALUE }
        } else opt.options
        val sel = RailSelect(opt.name)
        sel.items = displayOptions.map { RailSelect.Item(value = it.value, label = it.name, group = it.group) }
        sel.selectedValue = opt.currentValue
        sel.onSelect = { v -> if (!updatingConfigBar) selectConfigOption(opt.id, v) }
        railSelects = railSelects + (opt.id to sel)
        return sel
    }

    /* --------------------------- entry widgets ---------------------------- */

    private fun addEntry(e: Entry) {
        entries.add(e)
        entriesPanel.add(e.component)
        entriesPanel.revalidate()
        entriesPanel.repaint()
        scrollToBottom()
        syncCards()
    }

    private fun addAssistantEntry(): TextEntry {
        val row = AssistantMessageRow()
        val entry = TextEntry(row) { md -> row.setMarkdown(md) }
        addEntry(entry)
        return entry
    }

    private fun addThoughtEntry(): TextEntry {
        val card = ThoughtCard()
        val entry = TextEntry(card) { text -> card.setText(text) }
        addEntry(entry)
        return entry
    }

    /** user_message_chunk (echoed prompts) stream into a bubble too. */
    private fun addUserStreamingEntry(): TextEntry {
        val row = UserMessageRow("")
        val entry = TextEntry(row) { text -> row.setText(text) }
        addEntry(entry)
        return entry
    }

    private fun addUserMessage(text: String) {
        endStreaming()
        addEntry(Entry(UserMessageRow(text)))
        scrollToBottom(force = true)
    }

    private fun addSystemMessage(text: String) {
        endStreaming()
        addEntry(Entry(SystemMessageRow(text)))
    }

    private fun addError(text: String) {
        endStreaming()
        addEntry(Entry(ErrorMessageRow(text)))
    }

    private fun clearEntries() {
        endStreaming()
        entries.clear()
        toolEntries.clear()
        planCard = null
        historyPlaceholder = null
        entriesPanel.removeAll()
        entriesPanel.revalidate()
        entriesPanel.repaint()
        autoScroll = true
        syncCards()
    }

    /** Keep the newest output in view — unless the user scrolled up to read. */
    private fun scrollToBottom(force: Boolean = false) {
        if (force) autoScroll = true
        if (!autoScroll) return
        SwingUtilities.invokeLater {
            if (!autoScroll) return@invokeLater
            scroll.validate() // settle pending row heights first
            val m = scroll.verticalScrollBar.model
            m.value = m.maximum - m.extent
        }
    }

    private fun rootCause(t: Throwable): Throwable {
        var cause = t
        while (cause is java.util.concurrent.CompletionException || cause is java.util.concurrent.ExecutionException) {
            cause = cause.cause ?: break
        }
        return cause
    }

    private fun isCancellation(t: Throwable?): Boolean =
        t != null && rootCause(t) is java.util.concurrent.CancellationException

    private fun rootMessage(t: Throwable): String = rootCause(t).let { it.message ?: it.toString() }

    override fun dispose() {
        disposed = true
        sessionRequest = null
        sessionEpoch++
        slashPopup.hide()
        filePopup.hide()
        renderTimer.stop()
        updateListener?.let(service::removeUpdateListener)
        service.removeStateListener(stateListener)
        bridge.removeListener(bridgeListener)
        bridgeAttachExecutor.shutdownNow()
        if (service.permissionResponder != null) service.permissionResponder = null
    }
}
