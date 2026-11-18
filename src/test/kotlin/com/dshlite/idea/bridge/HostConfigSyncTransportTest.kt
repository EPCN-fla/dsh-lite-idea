package com.dshlite.idea.bridge

import com.dshlite.idea.launcher.ShellEnv
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConfigSyncTransportTest {
    @Test fun `probe quotes the source profile and keeps the default home fallback`() {
        val source = "team' profile; printf INJECTED"
        val script = buildProfileProbeScript(source)

        assertTrue(script.startsWith("DSH_HOME=\"\${DSH_HOME:-\$HOME/.dsh}\"\n"))
        assertTrue(script.contains("PROFILE_NAME=${ShellEnv.shellQuote(source)}\n"))
        assertTrue(script.contains("PROFILE=\"\$DSH_HOME/profiles/\$PROFILE_NAME\""))
        assertTrue(script.contains("if [ -f \"\$PROFILE/package.json\" ]"))
        assertFalse(script.startsWith("export DSH_HOME="))
    }

    @Test fun `host scripts quote custom DSH_HOME and the complete JSON request`() {
        val home = "/tmp/dsh home/'quoted'"
        val request = JsonObject().apply {
            addProperty("operation", "import")
            addProperty("source", "team'; printf INJECTED; '")
            addProperty("target", "acp-vscode")
            addProperty("models", true)
            addProperty("presets", false)
            addProperty("overwrite", true)
            addProperty("revision", "abc123'; touch /tmp/not-run; '")
        }
        val hostCode = "console.log(process.env.DSH_IMPORT_REQUEST)\n"
        val script = buildProfileImportScript(hostCode, "dsh", request, home)
        val homePrefix = "export DSH_HOME=${ShellEnv.shellQuote(home)}\n"
        val requestJson = Gson().toJson(request)

        assertTrue(buildProfileProbeScript("web", home).startsWith(homePrefix))
        assertTrue(script.contains("\n$homePrefix"))
        assertTrue(
            script.contains(
                "DSH_IMPORT_REQUEST=${ShellEnv.shellQuote(requestJson)}\nexport DSH_IMPORT_REQUEST",
            ),
        )
        assertTrue(script.contains("if ! command -v node >/dev/null 2>&1; then"))
        assertTrue(script.contains("{\"ok\":false,\"code\":\"config/node-unavailable\"}"))
        assertTrue(script.contains("{\"ok\":false,\"code\":\"config/unavailable\"}"))
    }

    @Test fun `heredoc marker avoids LF and CRLF collisions and cannot join the host final line`() {
        val marker = "DSH_PROFILE_IMPORT_HOST"
        val cases = listOf(
            Pair("plain host code", marker),
            Pair("before\n$marker\nafter", "${marker}_X"),
            Pair("before\r\n$marker\r\nafter", "${marker}_X"),
            Pair("$marker\n${marker}_X", "${marker}_X_X"),
        )
        val request = JsonObject().apply { addProperty("operation", "preview") }

        for ((hostCode, expectedMarker) in cases) {
            val script = buildProfileImportScript(hostCode, "dsh", request)
            val hostBody = if (hostCode.endsWith('\n')) hostCode else hostCode + '\n'

            assertTrue(hostCode, script.contains("<<'$expectedMarker'\n"))
            assertTrue(hostCode, script.contains("$hostBody$expectedMarker\nif ["))
            if (expectedMarker != marker) {
                assertFalse(hostCode, script.contains("<<'$marker'\n"))
            }
        }
    }

    @Test fun `parser returns the summary from only the final JSON line`() {
        val summary = summaryJson()
        val earlierFailure = JsonObject().apply {
            addProperty("ok", false)
            addProperty("code", "config/stale-preview")
        }
        val success = JsonObject().apply {
            addProperty("ok", true)
            add("summary", summary)
        }

        val parsed = parseProfileImportHostResult(
            "host diagnostic without configuration\n$earlierFailure\n$success\n",
        )

        assertEquals("team-source", parsed.source)
        assertEquals("acp-vscode", parsed.target)
        assertEquals("b".repeat(64), parsed.revision)
        assertEquals(listOf("custom-provider"), parsed.providers)
        assertEquals(listOf("custom-preset"), parsed.presets)
        assertEquals(listOf("provider:existing"), parsed.skipped)
        assertEquals(listOf("agent-preset-registry"), parsed.supportRows)
        assertTrue(parsed.changed)
        assertFalse(parsed.restartRequired)
        assertEquals("/host/profile/backup.yml", parsed.backupPath)
    }

    @Test fun `parser preserves stable error codes and required package identifiers`() {
        val codes = listOf(
            "config/stale-preview",
            "config/node-unavailable",
            "config/import-busy",
            "config/unavailable",
            "config/same-profile",
            "config/missing-package",
            "config/shared-override",
        )
        for (code in codes) {
            val payload = JsonObject().apply {
                addProperty("ok", false)
                addProperty("code", code)
                if (code == "config/missing-package") {
                    add("packages", JsonArray().apply {
                        add("@dsh/agent-preset-registry")
                        add(42)
                        add("@dsh/another-package")
                    })
                }
            }
            val error = assertThrows(ProfileImportException::class.java) {
                parseProfileImportHostResult("diagnostic SECRET\n$payload")
            }

            assertEquals(code, error.code)
            assertEquals(code, error.message)
            if (code == "config/missing-package") {
                assertEquals(listOf("@dsh/agent-preset-registry", "@dsh/another-package"), error.packages)
            } else {
                assertTrue(error.packages.isEmpty())
            }
        }
    }

    @Test fun `malformed or nonfinal JSON becomes unavailable without exposing output`() {
        val outputs = listOf(
            "",
            "SECRET",
            "{\"ok\":true}",
            "{\"ok\":true,\"summary\":[]}",
            "{\"ok\":false,\"code\":\"config/stale-preview\"}\nSECRET trailing",
        )
        for (output in outputs) {
            val error = assertThrows(ProfileImportException::class.java) {
                parseProfileImportHostResult(output)
            }
            assertEquals("config/unavailable", error.code)
            assertEquals("config/unavailable", error.message)
            assertFalse(error.message.orEmpty().contains("SECRET"))
            assertTrue(error.packages.isEmpty())
        }

        val missingCode = assertThrows(ProfileImportException::class.java) {
            parseProfileImportHostResult("{\"ok\":false}")
        }
        assertEquals("config/unavailable", missingCode.code)
        assertNull(missingCode.cause)
    }

    private fun summaryJson(): JsonObject = JsonObject().apply {
        addProperty("source", "team-source")
        addProperty("target", "acp-vscode")
        addProperty("revision", "b".repeat(64))
        add("providers", JsonArray().apply { add("custom-provider") })
        add("presets", JsonArray().apply { add("custom-preset") })
        add("skipped", JsonArray().apply { add("provider:existing") })
        add("supportRows", JsonArray().apply { add("agent-preset-registry") })
        addProperty("changed", true)
        addProperty("restartRequired", false)
        addProperty("backupPath", "/host/profile/backup.yml")
    }
    @Test fun `generated host launcher is valid bash without executing it`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val request = JsonObject().apply {
            addProperty("operation", "preview")
            addProperty("source", "team-source")
            addProperty("target", "acp-vscode")
        }
        val process = ProcessBuilder("/bin/bash", "-n").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use {
            it.write(buildProfileImportScript("console.log('syntax only')\n", "dsh", request))
        }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

}
