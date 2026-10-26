package com.dshlite.idea.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WslPathTest {
    @Test fun `maps drive paths without processes`() {
        assertEquals("/mnt/c/Program Files/project", toWslPath("C:\\Program Files\\project"))
        assertEquals("/mnt/d/project", toWslPath("D:/project"))
    }

    @Test fun `maps both WSL UNC hosts and the distro root`() {
        assertEquals("/home/me/project", toWslPath("\\\\wsl.localhost\\Ubuntu\\home\\me\\project"))
        assertEquals("/home/me/project", toWslPath("//wsl$/Ubuntu/home/me/project"))
        assertEquals("/", toWslPath("//WSL.LOCALHOST/Ubuntu"))
        assertEquals("/", toWslPath("//wsl$/Ubuntu/"))
    }

    @Test fun `leaves local Linux paths and unrelated shares alone`() {
        for (path in listOf("/home/me/project", "//server/share/folder", "//wsl.localhost.evil/distro/path")) {
            assertEquals(path, toWslPath(path))
        }
    }
    @Test fun `encodes WSL resource links as file URIs`() {
        val settings = com.dshlite.idea.settings.DshSettingsState()
        val uri = WslLauncher(settings).toDshResourceUri(
            "\\\\wsl.localhost\\Ubuntu\\home\\me\\a b#%中.txt"
        )
        assertEquals("file:///home/me/a%20b%23%25%E4%B8%AD.txt", uri)
    }

    @Test fun `launch uses the distro from the workspace UNC`() {
        val spec = WslLauncher(com.dshlite.idea.settings.DshSettingsState())
            .buildLaunchSpec("//wsl.localhost/Ubuntu/home/me/project")
        assertEquals(listOf("wsl.exe", "-d", "Ubuntu", "-e", "bash", "-lc"), spec.command.dropLast(1))
        assertTrue(spec.command.last().contains("cd '/home/me/project'"))
    }

    @Test fun `launch rejects a configured distro that conflicts with the workspace UNC`() {
        val settings = com.dshlite.idea.settings.DshSettingsState().apply { wslDistro = "Debian" }
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WslLauncher(settings).buildLaunchSpec("//wsl$/Ubuntu/home/me/project")
        }
        assertTrue(error.message!!.contains("Ubuntu"))
        assertTrue(error.message!!.contains("Debian"))
    }

}
