package com.dshlite.idea.launcher

import com.dshlite.idea.settings.DshSettingsState
import com.intellij.openapi.util.SystemInfoRt
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

class TargetShellTest {
    @Test fun `WSL helper commands inherit quoted custom environment`() {
        val settings = DshSettingsState().apply {
            wslDistro = "Ubuntu"
            env = "DSH_HOME=/custom home/it's-dsh\nBAD-KEY=value"
        }
        val spec = WslLauncher(settings).buildShellSpec("cat history")
        assertEquals(listOf("wsl.exe", "-d", "Ubuntu", "-e", "bash", "-lc"), spec.command.dropLast(1))
        assertTrue(spec.command.last().contains("export DSH_HOME='/custom home/it'\\''s-dsh';"))
        assertFalse(spec.command.last().contains("BAD-KEY"))
        assertTrue(spec.command.last().endsWith("cat history"))
    }

    @Test fun `local helper commands inherit custom environment`() {
        assumeFalse(SystemInfoRt.isWindows)
        val settings = DshSettingsState().apply { env = "DSH_HOME=/tmp/custom home" }
        val spec = LocalLauncher(settings).buildShellSpec("pwd")!!
        assertEquals("/tmp/custom home", spec.env["DSH_HOME"])
    }
    @Test fun `runtime topology is shared across host platforms`() {
        assertTrue(usesWsl("auto", isWindows = true))
        assertTrue(usesWsl("wsl", isWindows = true))
        assertFalse(usesWsl("windows", isWindows = true))
        assertFalse(usesWsl("local", isWindows = true))
        assertFalse(usesWsl("wsl", isWindows = false))
    }

    @Test fun `one shot target scripts bootstrap then export quoted environment`() {
        val script = ShellEnv.withTargetEnvironment(
            "echo run",
            mapOf("DSH_HOME" to "/custom home/it's-dsh", "PATH" to "/custom/bin:\$PATH"),
        )
        assertTrue(script.startsWith(ShellEnv.posixBootstrap()))
        assertTrue(script.contains("export DSH_HOME='/custom home/it'\\''s-dsh';"))
        assertTrue(script.indexOf("export DSH_HOME") < script.indexOf("echo run"))
        assertTrue(script.indexOf(ShellEnv.posixBootstrap()) < script.indexOf("export DSH_HOME"))
    }

    @Test fun `doctor probes use the configured target environment`() {
        val settings = DshSettingsState().apply { env = "DSH_HOME=/tmp/custom home" }
        val wsl = WslLauncher(settings).buildDoctorSpec().command.last()
        assertTrue(wsl.contains("export DSH_HOME='/tmp/custom home';"))
        assertTrue(wsl.indexOf(ShellEnv.posixBootstrap()) < wsl.indexOf("export DSH_HOME"))
        if (!SystemInfoRt.isWindows) {
            assertEquals("/tmp/custom home", LocalLauncher(settings).buildDoctorSpec().env["DSH_HOME"])
        }
    }

    @Test fun `workspace UNC distro also applies to shell and doctor helpers`() {
        val settings = DshSettingsState()
        val workspace = "//wsl.localhost/Ubuntu/home/me/project"
        val launcher = WslLauncher(settings, workspace)
        assertEquals("Ubuntu", wslDistroForWorkspace(settings, workspace))
        for (spec in listOf(launcher.buildShellSpec("pwd"), launcher.buildDoctorSpec())) {
            assertEquals(listOf("wsl.exe", "-d", "Ubuntu", "-e", "bash", "-lc"), spec.command.dropLast(1))
        }
    }

}
