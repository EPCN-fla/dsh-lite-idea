package com.dshlite.idea.bridge

import com.dshlite.idea.DshBundle
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class ConfigSyncFlowTest {
    private data class PreviewCall(
        val source: String,
        val target: String,
        val options: ConfigSyncOptions,
    )

    private data class ImportCall(
        val source: String,
        val target: String,
        val options: ConfigSyncOptions,
        val revision: String,
    )

    private class FakeRpc(configSync: Boolean = true) : ConfigSyncRpc {
        override val capabilities = BridgeCapabilities(configSync = configSync)

        override fun request(
            method: String,
            params: JsonObject?,
            timeoutMs: Long?,
        ): CompletableFuture<JsonObject?> = throw AssertionError("Unexpected legacy config RPC: $method")
    }

    private class FakeConnection(var rpc: FakeRpc?) : ConfigSyncConnection {
        override var isOn: Boolean = true
        override val client: ConfigSyncRpc? get() = rpc
        var reconnects = 0
        override fun reconnect() { reconnects++ }
    }

    private class FakeTransport(private val events: MutableList<String>) : ConfigSyncTransport {
        val probes = mutableListOf<String>()
        val previews = mutableListOf<PreviewCall>()
        val imports = mutableListOf<ImportCall>()
        var sourceExists = true
        var previewResult = summary()
        var importResult = summary().copy(backupPath = "/host/profile/backup.yml")
        var probeFailure: Throwable? = null
        var previewFailure: Throwable? = null
        var importFailure: Throwable? = null
        var synchronousFailure: String? = null
        var deferredProbe: CompletableFuture<Boolean>? = null
        var deferredPreview: CompletableFuture<ConfigSyncSummary>? = null
        var onProbe: (() -> Unit)? = null
        var onPreview: (() -> Unit)? = null

        override fun probeSource(source: String): CompletableFuture<Boolean> {
            probes += source
            events += "probe:$source"
            if (synchronousFailure == "probe") throw IllegalStateException("SECRET")
            onProbe?.invoke()
            deferredProbe?.let { return it }
            probeFailure?.let { return CompletableFuture.failedFuture(it) }
            return CompletableFuture.completedFuture(sourceExists)
        }

        override fun preview(
            source: String,
            target: String,
            options: ConfigSyncOptions,
        ): CompletableFuture<ConfigSyncSummary> {
            previews += PreviewCall(source, target, options)
            events += "preview:$source->$target"
            if (synchronousFailure == "preview") throw IllegalStateException("SECRET")
            onPreview?.invoke()
            deferredPreview?.let { return it }
            previewFailure?.let { return CompletableFuture.failedFuture(it) }
            return CompletableFuture.completedFuture(previewResult)
        }

        override fun import(
            source: String,
            target: String,
            options: ConfigSyncOptions,
            revision: String,
        ): CompletableFuture<ConfigSyncSummary> {
            imports += ImportCall(source, target, options, revision)
            events += "import:$source->$target@$revision"
            if (synchronousFailure == "import") throw IllegalStateException("SECRET")
            importFailure?.let { return CompletableFuture.failedFuture(it) }
            return CompletableFuture.completedFuture(importResult)
        }
    }

    private class FakeUi(private val events: MutableList<String>) : ConfigSyncInteractions {
        val sources = ArrayDeque<String?>()
        val scopes = ArrayDeque<ConfigSyncOptions?>()
        val policies = ArrayDeque<Boolean?>()
        val sourceTargets = mutableListOf<String>()
        var confirmation = true
        var onConfirm: (() -> Unit)? = null
        val confirmations = mutableListOf<String>()
        val infos = mutableListOf<Pair<String, String?>>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun chooseSource(target: String): String? {
            sourceTargets += target
            events += "source"
            return sources.removeFirstOrNull()
        }

        override fun chooseScope(): ConfigSyncOptions? {
            events += "scope"
            return scopes.removeFirstOrNull()
        }

        override fun chooseOverwrite(): Boolean? {
            events += "policy"
            return policies.removeFirstOrNull()
        }

        override fun confirm(preview: String): Boolean {
            events += "confirm"
            confirmations += preview
            onConfirm?.invoke()
            return confirmation
        }

        override fun info(message: String, detail: String?) { infos += message to detail }
        override fun warning(message: String) { warnings += message }
        override fun error(message: String) { errors += message }
    }

    private class Fixture(configSync: Boolean = true) {
        val events = mutableListOf<String>()
        val rpc = FakeRpc(configSync)
        val connection = FakeConnection(rpc)
        val transport = FakeTransport(events)
        val ui = FakeUi(events)
        var targetProfile = "acp-vscode"
        val imported = mutableListOf<ConfigSyncSummary>()
        val flow = ConfigSyncFlow(
            connection = connection,
            transport = transport,
            ui = ui,
            onEdt = { it() },
            targetProfile = { targetProfile },
            onImported = { imported += it },
        )

        init {
            enqueueRun()
        }

        fun enqueueRun(
            source: String? = "web",
            scope: ConfigSyncOptions? = ConfigSyncOptions(models = true, presets = true),
            overwrite: Boolean? = false,
        ) {
            ui.sources += source
            ui.scopes += scope
            ui.policies += overwrite
        }
    }

    @Test fun `imports exactly reviewed options and revision`() {
        val f = Fixture()
        f.flow.run()

        assertEquals(listOf("web"), f.transport.probes)
        val preview = f.transport.previews.single()
        val imported = f.transport.imports.single()
        assertEquals(PreviewCall("web", "acp-vscode", ConfigSyncOptions(true, true, false)), preview)
        assertEquals(imported.source, preview.source)
        assertEquals(imported.target, preview.target)
        assertEquals(imported.options, preview.options)
        assertEquals("a".repeat(64), imported.revision)

        val confirmation = f.ui.confirmations.single()
        for (id in listOf(
            "web", "acp-vscode", "custom-provider", "custom-preset",
            "agent-preset-registry", "provider:existing",
        )) {
            assertTrue(id, confirmation.contains(id))
        }
        val result = f.ui.infos.single()
        assertEquals(DshBundle.message("configSync.success"), result.first)
        assertTrue(result.second!!.contains("/host/profile/backup.yml"))
        assertTrue(result.second!!.contains("acp-vscode"))
        assertTrue(f.ui.errors.isEmpty())
        assertEquals(listOf("acp-vscode"), f.imported.map { it.target })
    }

    @Test fun `model only overwrite and preset only scopes preserve options`() {
        val overwrite = Fixture()
        overwrite.ui.scopes.clear()
        overwrite.ui.scopes += ConfigSyncOptions(models = true, presets = false)
        overwrite.ui.policies.clear()
        overwrite.ui.policies += true
        overwrite.flow.run()
        assertEquals(ConfigSyncOptions(true, false, true), overwrite.transport.previews.single().options)
        assertEquals(ConfigSyncOptions(true, false, true), overwrite.transport.imports.single().options)

        val presets = Fixture()
        presets.ui.scopes.clear()
        presets.ui.scopes += ConfigSyncOptions(models = false, presets = true)
        presets.flow.run()
        assertEquals(ConfigSyncOptions(false, true, false), presets.transport.previews.single().options)
        assertEquals(ConfigSyncOptions(false, true, false), presets.transport.imports.single().options)
    }

    @Test fun `custom source is trimmed and probed before options and preview`() {
        val f = Fixture()
        f.ui.sources.clear()
        f.ui.sources += "  team-source  "
        f.transport.previewResult = summary(source = "team-source")
        f.transport.importResult = summary(source = "team-source").copy(backupPath = "/host/backup.yml")

        f.flow.run()

        assertEquals(listOf("acp-vscode"), f.ui.sourceTargets)
        assertEquals(listOf("team-source"), f.transport.probes)
        assertEquals("team-source", f.transport.previews.single().source)
        assertEquals("team-source", f.transport.imports.single().source)
        assertEquals(
            listOf("source", "probe:team-source", "scope", "policy", "preview:team-source->acp-vscode", "confirm"),
            f.events.take(6),
        )
        assertTrue(f.events[6].startsWith("import:team-source->acp-vscode@"))
    }

    @Test fun `missing source stops before options and preview and releases the flow`() {
        val f = Fixture()
        f.transport.sourceExists = false
        f.flow.run()

        assertEquals(listOf("web"), f.transport.probes)
        assertTrue(f.transport.previews.isEmpty())
        assertTrue(f.transport.imports.isEmpty())
        assertEquals(listOf("source", "probe:web"), f.events)
        assertEquals(DshBundle.message("configSync.sourceMissing", "web"), f.ui.warnings.single())

        f.transport.sourceExists = true
        f.enqueueRun()
        f.flow.run()
        assertEquals(2, f.transport.probes.size)
        assertTrue(f.ui.infos.none { it.first == DshBundle.message("configSync.alreadyOpen") })
    }

    @Test fun `invalid profile names are rejected before probing`() {
        for (source in listOf("", "   ", ".", "..", "node_modules", "../web", "a/b", "a\\b", "a\u0000b")) {
            val f = Fixture()
            f.ui.sources.clear()
            f.ui.sources += source
            f.flow.run()

            assertEquals(source, DshBundle.message("configSync.source.invalid"), f.ui.warnings.single())
            assertTrue(source, f.transport.probes.isEmpty())
            assertTrue(source, f.transport.previews.isEmpty())
            assertTrue(source, f.transport.imports.isEmpty())
            assertTrue(source, f.ui.errors.isEmpty())
        }
    }

    @Test fun `host only run without a bridge still probes previews and imports`() {
        val f = Fixture()
        f.connection.isOn = false
        f.connection.rpc = null

        f.flow.run(requireBridge = false)

        assertEquals(0, f.connection.reconnects)
        assertEquals(listOf("web"), f.transport.probes)
        assertEquals(1, f.transport.previews.size)
        assertEquals(1, f.transport.imports.size)
        assertEquals(listOf("acp-vscode"), f.imported.map { it.target })
        assertTrue(f.ui.warnings.isEmpty())
        assertTrue(f.ui.errors.isEmpty())
    }

    @Test fun `cancellation unchanged preview and missing capability never import`() {
        for (stage in listOf("source", "scope", "policy", "confirmation", "unchanged")) {
            val f = Fixture()
            when (stage) {
                "source" -> {
                    f.ui.sources.clear()
                    f.ui.sources += null
                }
                "scope" -> {
                    f.ui.scopes.clear()
                    f.ui.scopes += null
                }
                "policy" -> {
                    f.ui.policies.clear()
                    f.ui.policies += null
                }
                "confirmation" -> f.ui.confirmation = false
                "unchanged" -> f.transport.previewResult = summary(changed = false)
            }
            f.flow.run()

            assertTrue(stage, f.transport.imports.isEmpty())
            assertTrue(stage, f.imported.isEmpty())
            assertTrue(stage, f.ui.errors.isEmpty())
            if (stage == "source") assertTrue(f.transport.probes.isEmpty())
            if (stage == "unchanged") {
                assertTrue(f.ui.confirmations.isEmpty())
                assertEquals(DshBundle.message("configSync.noChanges"), f.ui.infos.single().first)
            }
        }

        val unsupported = Fixture(configSync = false)
        unsupported.flow.run()
        assertTrue(unsupported.transport.probes.isEmpty())
        assertTrue(unsupported.transport.previews.isEmpty())
        assertTrue(unsupported.transport.imports.isEmpty())
        assertTrue(unsupported.ui.warnings.single().contains("0.4.0"))
    }

    @Test fun `disconnected bridge requests reconnect without host transport calls`() {
        val f = Fixture()
        f.connection.isOn = false
        f.connection.rpc = null
        f.flow.run()

        assertEquals(1, f.connection.reconnects)
        assertTrue(f.transport.probes.isEmpty())
        assertTrue(f.transport.previews.isEmpty())
        assertTrue(f.transport.imports.isEmpty())
        assertEquals(DshBundle.message("configSync.noBridge"), f.ui.warnings.single())
    }

    @Test fun `stale preview is not retried and diagnostics stay sanitized`() {
        val f = Fixture()
        f.transport.importFailure = BridgeException("SECRET YAML", -32009, "config/stale-preview")
        f.flow.run()

        assertEquals(1, f.transport.imports.size)
        assertEquals(DshBundle.message("configSync.error.stale"), f.ui.errors.single())
        assertFalse(f.ui.errors.single().contains("SECRET"))
        assertTrue(f.imported.isEmpty())
    }

    @Test fun `connection replacement during confirmation cannot import elsewhere`() {
        val f = Fixture()
        f.ui.onConfirm = { f.connection.rpc = FakeRpc() }
        f.flow.run()

        assertEquals(1, f.transport.previews.size)
        assertTrue(f.transport.imports.isEmpty())
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
        assertTrue(f.imported.isEmpty())
    }

    @Test fun `connection replacement while preview is in flight discards result`() {
        val f = Fixture()
        f.transport.onPreview = { f.connection.rpc = FakeRpc() }
        f.flow.run()

        assertTrue(f.ui.confirmations.isEmpty())
        assertTrue(f.transport.imports.isEmpty())
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
    }

    @Test fun `target profile replacement at every checkpoint prevents writes`() {
        for (stage in listOf("probe", "preview", "confirmation")) {
            val f = Fixture()
            when (stage) {
                "probe" -> f.transport.onProbe = { f.targetProfile = "other-profile" }
                "preview" -> f.transport.onPreview = { f.targetProfile = "other-profile" }
                "confirmation" -> f.ui.onConfirm = { f.targetProfile = "other-profile" }
            }
            f.flow.run()

            assertEquals(stage, DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
            assertTrue(stage, f.transport.imports.isEmpty())
            assertTrue(stage, f.imported.isEmpty())
            if (stage == "probe") assertTrue(f.transport.previews.isEmpty())
            if (stage != "confirmation") assertTrue(f.ui.confirmations.isEmpty())
        }
    }

    @Test fun `overlapping invocation is blocked and cancellation releases the lock`() {
        val f = Fixture()
        val pending = CompletableFuture<ConfigSyncSummary>()
        f.transport.deferredPreview = pending
        f.ui.confirmation = false
        f.flow.run()
        f.flow.run()

        assertEquals(1, f.transport.probes.size)
        assertEquals(1, f.transport.previews.size)
        assertEquals(DshBundle.message("configSync.alreadyOpen"), f.ui.infos.single().first)
        pending.complete(summary())
        assertEquals(1, f.ui.confirmations.size)

        f.transport.deferredPreview = null
        f.enqueueRun()
        f.flow.run()
        assertEquals(2, f.transport.previews.size)
        assertEquals(1, f.ui.infos.count { it.first == DshBundle.message("configSync.alreadyOpen") })
    }

    @Test fun `uncertain write outcome is neither definite failure nor retried`() {
        val f = Fixture()
        f.transport.importFailure = BridgeException("SECRET", -32000, "bridge/timeout")
        f.flow.run()

        assertEquals(1, f.transport.imports.size)
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
        assertFalse(f.ui.errors.single().contains("SECRET"))
        assertTrue(f.imported.isEmpty())
        assertTrue(f.ui.infos.none { it.first == DshBundle.message("configSync.success") })
    }

    @Test fun `node unavailable and all stable failures use safe diagnostics`() {
        val mappings = mapOf(
            "config/stale-preview" to "configSync.error.stale",
            "config/node-unavailable" to "configSync.error.nodeUnavailable",
            "config/import-busy" to "configSync.error.busy",
            "config/unavailable" to "configSync.error.unavailable",
            "service-unavailable" to "configSync.error.unavailable",
            "config/same-profile" to "configSync.error.sameProfile",
            "config/missing-package" to "configSync.error.missingPackage",
            "config/shared-override" to "configSync.error.sharedOverride",
            "config/row-collision" to "configSync.error.unknown",
        )
        for ((code, key) in mappings) {
            val bridgeText = configSyncErrorText(
                ExecutionException(CompletionException(BridgeException("SECRET", -32009, code))),
            )
            assertEquals(code, DshBundle.message(key), bridgeText)
            assertFalse(code, bridgeText.contains("SECRET"))
        }

        val nodeError = configSyncErrorText(
            CompletionException(ProfileImportException("config/node-unavailable")),
        )
        assertEquals(DshBundle.message("configSync.error.nodeUnavailable"), nodeError)
        assertFalse(configSyncErrorText(IllegalStateException("SECRET")).contains("SECRET"))

        val f = Fixture()
        f.transport.previewFailure = ProfileImportException("config/node-unavailable")
        f.flow.run()
        assertEquals(DshBundle.message("configSync.error.nodeUnavailable"), f.ui.errors.single())
        assertTrue(f.transport.imports.isEmpty())
    }

    @Test fun `synchronous transport failures release the flow for a later attempt`() {
        for (operation in listOf("probe", "preview", "import")) {
            val f = Fixture()
            f.transport.synchronousFailure = operation
            f.flow.run()
            assertEquals(operation, DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
            assertFalse(operation, f.ui.errors.single().contains("SECRET"))

            f.transport.synchronousFailure = null
            f.enqueueRun()
            f.flow.run()
            assertTrue(operation, f.ui.infos.none { it.first == DshBundle.message("configSync.alreadyOpen") })
            assertEquals(operation, 1, f.imported.size)
        }
    }

    private companion object {
        fun summary(
            source: String = "web",
            target: String = "acp-vscode",
            changed: Boolean = true,
        ) = ConfigSyncSummary(
            source = source,
            target = target,
            revision = "a".repeat(64),
            providers = listOf("custom-provider"),
            presets = listOf("custom-preset"),
            skipped = listOf("provider:existing"),
            supportRows = listOf("agent-preset-registry"),
            changed = changed,
            restartRequired = changed,
        )
    }
}
