package com.dshlite.idea.bridge

import com.dshlite.idea.DshBundle
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class ConfigSyncFlowTest {
    private data class Call(val method: String, val params: JsonObject, val timeoutMs: Long?)

    private class FakeRpc(configSync: Boolean = true) : ConfigSyncRpc {
        override val capabilities = BridgeCapabilities(configSync = configSync)
        val calls = mutableListOf<Call>()
        var preview: JsonObject = summary()
        var imported: JsonObject = summary().apply { addProperty("backupPath", "/host/profile/backup.yml") }
        var importFailure: Throwable? = null
        var deferredPreview: CompletableFuture<JsonObject?>? = null
        var onPreview: (() -> Unit)? = null

        override fun request(method: String, params: JsonObject?, timeoutMs: Long?): CompletableFuture<JsonObject?> {
            calls += Call(method, params ?: JsonObject(), timeoutMs)
            onPreview?.takeIf { method == "config.preview" }?.invoke()
            if (method == "config.preview") {
                deferredPreview?.let { return it }
                return CompletableFuture.completedFuture(preview)
            }
            importFailure?.let { return CompletableFuture.failedFuture(it) }
            return CompletableFuture.completedFuture(imported)
        }
    }

    private class FakeConnection(var rpc: FakeRpc?) : ConfigSyncConnection {
        override var isOn: Boolean = true
        override val client: ConfigSyncRpc? get() = rpc
        var reconnects = 0
        override fun reconnect() { reconnects++ }
    }

    private class FakeUi : ConfigSyncInteractions {
        val scopes = ArrayDeque<ConfigSyncOptions?>()
        val policies = ArrayDeque<Boolean?>()
        var confirmation = true
        var onConfirm: (() -> Unit)? = null
        val confirmations = mutableListOf<String>()
        val infos = mutableListOf<Pair<String, String?>>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun chooseScope(): ConfigSyncOptions? = scopes.removeFirstOrNull()
        override fun chooseOverwrite(): Boolean? = policies.removeFirstOrNull()
        override fun confirm(preview: String): Boolean {
            confirmations += preview
            onConfirm?.invoke()
            return confirmation
        }
        override fun info(message: String, detail: String?) { infos += message to detail }
        override fun warning(message: String) { warnings += message }
        override fun error(message: String) { errors += message }
    }

    private class Fixture(configSync: Boolean = true) {
        val rpc = FakeRpc(configSync)
        val connection = FakeConnection(rpc)
        val ui = FakeUi().apply {
            scopes += ConfigSyncOptions(models = true, presets = true)
            policies += false
        }
        val imported = mutableListOf<ConfigSyncSummary>()
        val flow = ConfigSyncFlow(connection, ui, { it() }, { imported += it })
    }

    @Test fun `imports exactly reviewed options and revision with bounded deadlines`() {
        val f = Fixture()
        f.flow.run()

        assertEquals(listOf("config.preview", "config.import"), f.rpc.calls.map { it.method })
        assertEquals(listOf(30_000L, 60_000L), f.rpc.calls.map { it.timeoutMs })
        val previewParams = f.rpc.calls[0].params
        val importParams = f.rpc.calls[1].params
        assertEquals("a".repeat(64), importParams.get("revision").asString)
        for (key in listOf("models", "presets", "overwrite")) {
            assertEquals(previewParams.get(key), importParams.get(key))
        }
        assertNull(previewParams.get("revision"))
        val confirm = f.ui.confirmations.single()
        for (id in listOf("web", "acp-vscode", "custom-provider", "custom-preset", "agent-preset-registry", "provider:existing")) {
            assertTrue(id, confirm.contains(id))
        }
        val result = f.ui.infos.single()
        assertTrue(result.second!!.contains("/host/profile/backup.yml"))
        assertTrue(result.second!!.contains("acp-vscode"))
        assertTrue(f.ui.errors.isEmpty())
        assertEquals(listOf("acp-vscode"), f.imported.map { it.target })
    }

    @Test fun `model only overwrite and preset only scopes preserve options`() {
        val overwrite = Fixture()
        overwrite.ui.scopes.clear(); overwrite.ui.scopes += ConfigSyncOptions(models = true, presets = false)
        overwrite.ui.policies.clear(); overwrite.ui.policies += true
        overwrite.flow.run()
        assertTrue(overwrite.rpc.calls[1].params.get("models").asBoolean)
        assertFalse(overwrite.rpc.calls[1].params.get("presets").asBoolean)
        assertTrue(overwrite.rpc.calls[1].params.get("overwrite").asBoolean)

        val presets = Fixture()
        presets.ui.scopes.clear(); presets.ui.scopes += ConfigSyncOptions(models = false, presets = true)
        presets.flow.run()
        assertFalse(presets.rpc.calls[0].params.get("models").asBoolean)
        assertTrue(presets.rpc.calls[0].params.get("presets").asBoolean)
    }

    @Test fun `cancellation unchanged preview and missing capability never import`() {
        for (stage in listOf("scope", "policy", "confirmation", "unchanged")) {
            val f = Fixture()
            when (stage) {
                "scope" -> { f.ui.scopes.clear(); f.ui.scopes += null }
                "policy" -> { f.ui.policies.clear(); f.ui.policies += null }
                "confirmation" -> f.ui.confirmation = false
                "unchanged" -> f.rpc.preview = summary(changed = false)
            }
            f.flow.run()
            assertTrue(stage, f.rpc.calls.none { it.method == "config.import" })
        }
        for (capability in listOf(false)) {
            val f = Fixture(configSync = capability)
            f.flow.run()
            assertTrue(f.rpc.calls.isEmpty())
            assertTrue(f.ui.warnings.single().contains("0.4.0"))
        }
    }

    @Test fun `disconnected bridge requests reconnect without config RPCs`() {
        val f = Fixture()
        f.connection.isOn = false
        f.connection.rpc = null
        f.flow.run()
        assertEquals(1, f.connection.reconnects)
        assertTrue(f.rpc.calls.isEmpty())
        assertEquals(1, f.ui.warnings.size)
    }

    @Test fun `stale preview is not retried and diagnostics stay sanitized`() {
        val f = Fixture()
        f.rpc.importFailure = BridgeException("SECRET YAML", -32009, "config/stale-preview")
        f.flow.run()
        assertEquals(1, f.rpc.calls.count { it.method == "config.import" })
        assertEquals(DshBundle.message("configSync.error.stale"), f.ui.errors.single())
        assertFalse(f.ui.errors.single().contains("SECRET"))
        assertTrue(f.imported.isEmpty())
    }

    @Test fun `connection replacement during confirmation cannot import elsewhere`() {
        val f = Fixture()
        f.ui.onConfirm = { f.connection.rpc = FakeRpc() }
        f.flow.run()
        assertEquals(listOf("config.preview"), f.rpc.calls.map { it.method })
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
    }

    @Test fun `connection replacement while preview is in flight discards result`() {
        val f = Fixture()
        f.rpc.onPreview = { f.connection.rpc = FakeRpc() }
        f.flow.run()
        assertTrue(f.ui.confirmations.isEmpty())
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
    }

    @Test fun `overlapping invocation is blocked and cancellation releases the lock`() {
        val f = Fixture()
        val pending = CompletableFuture<JsonObject?>()
        f.rpc.deferredPreview = pending
        f.ui.confirmation = false
        f.flow.run()
        f.flow.run()
        assertEquals(1, f.rpc.calls.size)
        assertEquals(DshBundle.message("configSync.alreadyOpen"), f.ui.infos.single().first)
        pending.complete(summary())
        assertTrue(f.ui.confirmations.isNotEmpty())

        f.rpc.deferredPreview = null
        f.ui.scopes += ConfigSyncOptions(models = true, presets = true)
        f.ui.policies += false
        f.flow.run()
        assertEquals(2, f.rpc.calls.count { it.method == "config.preview" })
    }

    @Test fun `uncertain write outcome is neither definite failure nor retried`() {
        val f = Fixture()
        f.rpc.importFailure = BridgeException("SECRET", -32000, "bridge/timeout")
        f.flow.run()
        assertEquals(1, f.rpc.calls.count { it.method == "config.import" })
        assertEquals(DshBundle.message("configSync.error.unknown"), f.ui.errors.single())
        assertFalse(f.ui.errors.single().contains("SECRET"))
    }

    @Test fun `all failures use safe stable code diagnostics`() {
        for (code in listOf(
            "config/import-busy", "config/unavailable", "config/same-profile",
            "config/missing-package", "config/shared-override", "config/row-collision",
        )) {
            val text = configSyncErrorText(BridgeException("SECRET", -32009, code))
            assertTrue(text.isNotBlank())
            assertFalse(text.contains("SECRET"))
        }
        assertFalse(configSyncErrorText(IllegalStateException("SECRET")).contains("SECRET"))
    }

    private companion object {
        fun summary(changed: Boolean = true): JsonObject = JsonObject().apply {
            addProperty("source", "web")
            addProperty("target", "acp-vscode")
            addProperty("revision", "a".repeat(64))
            add("providers", com.google.gson.JsonArray().apply { add("custom-provider") })
            add("presets", com.google.gson.JsonArray().apply { add("custom-preset") })
            add("skipped", com.google.gson.JsonArray().apply { add("provider:existing") })
            add("supportRows", com.google.gson.JsonArray().apply { add("agent-preset-registry") })
            addProperty("changed", changed)
            addProperty("restartRequired", changed)
        }
    }
}
