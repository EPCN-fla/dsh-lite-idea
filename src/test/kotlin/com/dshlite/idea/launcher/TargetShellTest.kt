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
}
