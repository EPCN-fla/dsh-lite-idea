package com.dshlite.idea.bridge

import com.intellij.openapi.util.SystemInfoRt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BridgeInstallationTest {
    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun runProbe(profile: String, home: Path, configuredHome: Path? = null): String {
        val process = ProcessBuilder("/bin/bash", "-c", buildBridgeInstallationProbeScript(profile, configuredHome?.toString()))
            .redirectErrorStream(true)
            .apply { environment()["DSH_HOME"] = home.toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    @Test fun `probe requires dependency and installed manifest but ignores activation edits`() {
        assumeFalse(SystemInfoRt.isWindows)
        val home = Files.createTempDirectory("bridge-installed-")
        val profile = home.resolve("profiles/acp-vscode")
        write(profile.resolve("package.json"), """{"dependencies":{"dsh-vscode-bridge":"0.5.3"}}""")
        write(profile.resolve("cordis.patch.yml"), "# manually disabled\n[]\n")
        assertEquals(BridgeInstallationState.MISSING, parseBridgeInstallationProbe(runProbe("acp-vscode", home)))

        write(profile.resolve("node_modules/dsh-vscode-bridge/package.json"), "{}")
        assertEquals(BridgeInstallationState.INSTALLED, parseBridgeInstallationProbe(runProbe("acp-vscode", home)))

        write(profile.resolve("package.json"), """{"dependencies":{}}""")
        assertEquals(BridgeInstallationState.MISSING, parseBridgeInstallationProbe(runProbe("acp-vscode", home)))
    }

    @Test fun `configured home and profile are quoted as data and override ambient home`() {
        assumeFalse(SystemInfoRt.isWindows)
        val root = Files.createTempDirectory("bridge-quote-")
        val configured = root.resolve("home with ' quote")
        val ambient = root.resolve("wrong-home")
        val profileName = "profile with ' quote"
        val profile = configured.resolve("profiles").resolve(profileName)
        write(profile.resolve("package.json"), """{"dependencies":{"dsh-vscode-bridge":"test"}}""")
        write(profile.resolve("node_modules/dsh-vscode-bridge/package.json"), "{}")
        val output = runProbe(profileName, ambient, configured)
        assertEquals(BridgeInstallationState.INSTALLED, parseBridgeInstallationProbe(output))
        assertFalse(Files.exists(ambient.resolve("profiles")))
    }

    @Test fun `unexpected probe output remains unknown`() {
        assertEquals(BridgeInstallationState.UNKNOWN, parseBridgeInstallationProbe(""))
        assertEquals(BridgeInstallationState.UNKNOWN, parseBridgeInstallationProbe("installed\nextra"))
        assertEquals(BridgeInstallationState.INSTALLED, parseBridgeInstallationProbe(" installed\n"))
        assertEquals(BridgeInstallationState.MISSING, parseBridgeInstallationProbe("missing\n"))
    }

    @Test fun `installed disconnected is repair rather than a missing package`() {
        for (startupFailed in listOf(false, true)) {
            val prompt = bridgeSetupPrompt(BridgeInstallationState.INSTALLED, startupFailed, "acp-vscode")
            assertTrue(prompt.message.contains("package is installed"))
            assertTrue(prompt.message.contains("cordis.patch.yml"))
            assertFalse(prompt.message.contains("was not detected"))
            assertEquals("修复 Bridge", prompt.actionLabel)
            assertEquals(startupFailed, prompt.message.contains("failed to start"))
        }
    }

    @Test fun `missing and unknown states retain distinct setup actions`() {
        val missing = bridgeSetupPrompt(BridgeInstallationState.MISSING, false, "acp-vscode")
        assertEquals("安装 Bridge", missing.actionLabel)
        assertTrue(missing.message.contains("Install it now"))

        val unknown = bridgeSetupPrompt(BridgeInstallationState.UNKNOWN, true, "acp-vscode")
        assertEquals("安装/修复 Bridge", unknown.actionLabel)
        assertTrue(unknown.message.contains("could not be confirmed"))
        assertTrue(unknown.message.contains("failed to start"))
    }
}
