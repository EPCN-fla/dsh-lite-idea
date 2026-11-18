/**
 * Explicit, preview-first source-profile -> active bridge profile import.
 * Only identifiers cross the UI; RPC error messages may contain configuration
 * excerpts and are therefore never displayed by this flow.
 */
package com.dshlite.idea.bridge

import com.dshlite.idea.DshBundle
import com.dshlite.idea.settings.DshSettingsState
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal data class ConfigSyncOptions(
    val models: Boolean,
    val presets: Boolean,
    val overwrite: Boolean = false,
)

internal data class ConfigSyncSummary(
    val source: String,
    val target: String,
    val revision: String,
    val providers: List<String>,
    val presets: List<String>,
    val skipped: List<String>,
    val supportRows: List<String>,
    val changed: Boolean,
    val restartRequired: Boolean,
    val backupPath: String? = null,
) {
    companion object {
        fun from(o: JsonObject): ConfigSyncSummary {
            fun string(key: String): String = o.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw IllegalArgumentException("Invalid config summary")
            fun strings(key: String): List<String> = o.getAsJsonArray(key)
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }
                ?: emptyList()
            fun bool(key: String): Boolean = o.get(key)
                ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            return ConfigSyncSummary(
                source = string("source"),
                target = string("target"),
                revision = string("revision"),
                providers = strings("providers"),
                presets = strings("presets"),
                skipped = strings("skipped"),
                supportRows = strings("supportRows"),
                changed = bool("changed"),
                restartRequired = bool("restartRequired"),
                backupPath = o.get("backupPath")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString,
            )
        }
    }
}

internal fun ConfigSyncOptions.params(revision: String? = null) = JsonObject().apply {
    addProperty("models", models)
    addProperty("presets", presets)
    addProperty("overwrite", overwrite)
    revision?.let { addProperty("revision", it) }
}

interface ConfigSyncRpc {
    val capabilities: BridgeCapabilities?
    fun request(method: String, params: JsonObject? = null, timeoutMs: Long? = null): CompletableFuture<JsonObject?>
}

internal interface ConfigSyncConnection {
    val isOn: Boolean
    val client: ConfigSyncRpc?
    fun reconnect()
}

internal interface ConfigSyncInteractions {
    fun chooseSource(target: String): String?
    fun chooseScope(): ConfigSyncOptions?
    fun chooseOverwrite(): Boolean?
    fun confirm(preview: String): Boolean
    fun info(message: String, detail: String? = null)
    fun warning(message: String)
    fun error(message: String)
}

internal class ConfigSyncFlow(
    private val connection: ConfigSyncConnection,
    private val transport: ConfigSyncTransport,
    private val ui: ConfigSyncInteractions,
    private val onEdt: (() -> Unit) -> Unit,
    private val targetProfile: () -> String,
    private val onImported: (ConfigSyncSummary) -> Unit = {},
) {
    constructor(
        project: Project,
        bridge: BridgeManager,
        onImported: (ConfigSyncSummary) -> Unit = {},
    ) : this(
        connection = object : ConfigSyncConnection {
            override val isOn: Boolean get() = bridge.isOn
            override val client: ConfigSyncRpc? get() = bridge.client
            override fun reconnect() = bridge.reconnect()
        },
        transport = HostConfigSyncTransport(project),
        ui = IdeaConfigSyncInteractions(project),
        onEdt = { block ->
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) block()
            }, ModalityState.any())
        },
        targetProfile = { DshSettingsState.getInstance().profile },
        onImported = onImported,
    )

    @Volatile private var running = false

    fun run(requireBridge: Boolean = true) {
        if (running) {
            ui.info(DshBundle.message("configSync.alreadyOpen"))
            return
        }
        running = true
        val client = connection.client
        if (requireBridge && (!connection.isOn || client == null)) {
            connection.reconnect()
            ui.warning(DshBundle.message("configSync.noBridge"))
            finish()
            return
        }
        if (requireBridge && client?.capabilities?.configSync != true) {
            ui.warning(DshBundle.message("configSync.unsupported"))
            finish()
            return
        }
        val target = targetProfile()
        val source = ui.chooseSource(target)?.trim() ?: run { finish(); return }
        validateProfileName(source)?.let {
            ui.warning(it)
            finish()
            return
        }
        if (!stillConnected(client, target, requireBridge)) {
            fail(IllegalStateException("bridge changed"))
            return
        }
        val request = try {
            transport.probeSource(source)
        } catch (e: Exception) {
            fail(e)
            return
        }
        request.whenComplete { exists, error ->
            onEdt {
                if (error != null) {
                    fail(error)
                    return@onEdt
                }
                if (exists != true) {
                    ui.warning(DshBundle.message("configSync.sourceMissing", source))
                    finish()
                    return@onEdt
                }
                chooseOptions(client, target, source, requireBridge)
            }
        }
    }

    private fun chooseOptions(
        client: ConfigSyncRpc?,
        target: String,
        source: String,
        requireBridge: Boolean,
    ) {
        if (!stillConnected(client, target, requireBridge)) {
            fail(IllegalStateException("bridge changed"))
            return
        }
        val scope = ui.chooseScope() ?: run { finish(); return }
        val overwrite = ui.chooseOverwrite() ?: run { finish(); return }
        val options = scope.copy(overwrite = overwrite)
        val request = try {
            transport.preview(source, target, options)
        } catch (e: Exception) {
            fail(e)
            return
        }
        request.whenComplete { result, error ->
            onEdt { handlePreview(client, target, source, requireBridge, options, result, error) }
        }
    }

    private fun handlePreview(
        client: ConfigSyncRpc?,
        target: String,
        source: String,
        requireBridge: Boolean,
        options: ConfigSyncOptions,
        result: ConfigSyncSummary?,
        error: Throwable?,
    ) {
        if (error != null) { fail(error); return }
        if (!stillConnected(client, target, requireBridge)) { fail(IllegalStateException("bridge changed")); return }
        val preview = result ?: run {
            fail(IllegalArgumentException("missing preview"))
            return
        }
        val detail = configSyncPreviewText(preview, options)
        if (!preview.changed) {
            ui.info(DshBundle.message("configSync.noChanges"), detail)
            finish()
            return
        }
        if (!ui.confirm(detail)) { finish(); return }
        if (!stillConnected(client, target, requireBridge)) { fail(IllegalStateException("bridge changed")); return }
        val request = try {
            transport.import(source, target, options, preview.revision)
        } catch (e: Exception) {
            fail(e)
            return
        }
        request.whenComplete { imported, importError ->
            onEdt { handleImport(imported, importError) }
        }
    }

    private fun handleImport(result: ConfigSyncSummary?, error: Throwable?) {
        if (error != null) { fail(error); return }
        val imported = result ?: run {
            fail(IllegalArgumentException("missing import result"))
            return
        }
        val detail = buildList {
            add(DshBundle.message("configSync.result.source", imported.source))
            add(DshBundle.message("configSync.result.target", imported.target))
            imported.backupPath?.let { add(DshBundle.message("configSync.result.backup", it)) }
            if (imported.restartRequired) add(DshBundle.message("configSync.result.restart"))
            add(DshBundle.message("configSync.result.restore"))
        }.joinToString("\n")
        if (imported.changed) onImported(imported)
        ui.info(
            DshBundle.message(if (imported.changed) "configSync.success" else "configSync.noChanges"),
            detail,
        )
        finish()
    }

    private fun stillConnected(client: ConfigSyncRpc?, target: String, requireBridge: Boolean): Boolean =
        targetProfile() == target && (!requireBridge || (connection.isOn && connection.client === client))

    private fun fail(error: Throwable) {
        ui.error(configSyncErrorText(error))
        finish()
    }

    private fun finish() {
        running = false
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 30_000L
        const val IMPORT_TIMEOUT_MS = 60_000L
    }
}

private class IdeaConfigSyncInteractions(private val project: Project) : ConfigSyncInteractions {
    override fun chooseSource(target: String): String? = Messages.showInputDialog(
        project,
        DshBundle.message("configSync.source.prompt", target),
        DshBundle.message("chat.importWebConfig"),
        null,
        "web",
        null,
    )

    override fun chooseScope(): ConfigSyncOptions? {
        val choices = arrayOf(
            DshBundle.message("configSync.scope.all"),
            DshBundle.message("configSync.scope.models"),
            DshBundle.message("configSync.scope.presets"),
        )
        return when (
            Messages.showDialog(
                project, DshBundle.message("configSync.scope.prompt"),
                DshBundle.message("chat.importWebConfig"), choices, 0, Messages.getInformationIcon(),
            )
        ) {
            0 -> ConfigSyncOptions(models = true, presets = true)
            1 -> ConfigSyncOptions(models = true, presets = false)
            2 -> ConfigSyncOptions(models = false, presets = true)
            else -> null
        }
    }

    override fun chooseOverwrite(): Boolean? {
        val choices = arrayOf(
            DshBundle.message("configSync.policy.keep"),
            DshBundle.message("configSync.policy.replace"),
        )
        return when (
            Messages.showDialog(
                project, DshBundle.message("configSync.policy.prompt"),
                DshBundle.message("chat.importWebConfig"), choices, 0, Messages.getWarningIcon(),
            )
        ) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    override fun confirm(preview: String): Boolean = Messages.showDialog(
        project,
        preview,
        DshBundle.message("configSync.confirm.title"),
        arrayOf(DshBundle.message("configSync.confirm.import"), DshBundle.message("configSync.confirm.cancel")),
        0,
        Messages.getWarningIcon(),
    ) == 0

    override fun info(message: String, detail: String?) {
        Messages.showInfoMessage(project, listOfNotNull(message, detail).joinToString("\n\n"),
            DshBundle.message("chat.importWebConfig"))
    }

    override fun warning(message: String) {
        Messages.showWarningDialog(project, message, DshBundle.message("chat.importWebConfig"))
    }

    override fun error(message: String) {
        Messages.showErrorDialog(project, message, DshBundle.message("chat.importWebConfig"))
    }
}

/** DSH accepts simple directory names, not paths or its dependency sentinel. */
internal fun validateProfileName(value: String): String? {
    val name = value.trim()
    return if (name.isEmpty() || name == "." || name == ".." || name == "node_modules" ||
        name.any { it == '/' || it == '\\' || it == '\u0000' }
    ) DshBundle.message("configSync.source.invalid") else null
}

internal fun configSyncPreviewText(summary: ConfigSyncSummary, options: ConfigSyncOptions): String {
    fun list(key: String, values: List<String>): String = DshBundle.message(
        key,
        if (values.isEmpty()) DshBundle.message("configSync.preview.none") else values.joinToString(", "),
    )
    return listOf(
        DshBundle.message("configSync.preview.sourceTarget", summary.source, summary.target),
        DshBundle.message(if (options.overwrite) "configSync.preview.replace" else "configSync.preview.keep"),
        list("configSync.preview.providers", summary.providers),
        list("configSync.preview.presets", summary.presets),
        list("configSync.preview.supportRows", summary.supportRows),
        list("configSync.preview.skipped", summary.skipped),
        "",
        DshBundle.message("configSync.preview.warning"),
        DshBundle.message("configSync.preview.limits"),
    ).joinToString("\n")
}

/** Map only stable codes; never echo configuration-bearing exception messages. */
internal fun configSyncErrorText(error: Throwable): String {
    val cause = generateSequence(error) {
        when (it) {
            is CompletionException, is ExecutionException -> it.cause
            else -> null
        }
    }.filterIsInstance<BridgeException>().firstOrNull()
    val profileError = generateSequence(error) {
        when (it) {
            is CompletionException, is ExecutionException -> it.cause
            else -> null
        }
    }.filterIsInstance<ProfileImportException>().firstOrNull()
    val code = cause?.dataCode ?: profileError?.code
    val key = when (code) {
        "config/stale-preview" -> "configSync.error.stale"
        "config/node-unavailable" -> "configSync.error.nodeUnavailable"
        "config/import-busy" -> "configSync.error.busy"
        "config/unavailable", "service-unavailable" -> "configSync.error.unavailable"
        "config/same-profile" -> "configSync.error.sameProfile"
        "config/missing-package" -> "configSync.error.missingPackage"
        "config/shared-override" -> "configSync.error.sharedOverride"
        else -> "configSync.error.unknown"
    }
    return DshBundle.message(key)
}
