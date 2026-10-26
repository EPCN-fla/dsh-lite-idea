package com.dshlite.idea.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSettingsStateTest {
    @Test fun `legacy default migrates once and later explicit acp selection survives reload`() {
        val settings = DshSettingsState()
        settings.loadState(DshSettingsState.Data().apply {
            profile = "acp"
            migratedProfile = false
        })

        assertEquals("acp-vscode", settings.profile)
        assertTrue(settings.state.migratedProfile)

        settings.profile = "acp"
        settings.loadState(settings.state)
        assertEquals("acp", settings.profile)
    }

    @Test fun `any explicit profile choice completes the legacy migration`() {
        val settings = DshSettingsState()
        settings.profile = "acp"

        assertTrue(settings.state.migratedProfile)
        settings.loadState(settings.state)
        assertEquals("acp", settings.profile)
    }
}
