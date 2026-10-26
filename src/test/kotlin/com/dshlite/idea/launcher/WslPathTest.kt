package com.dshlite.idea.launcher

import org.junit.Assert.assertEquals
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

}
