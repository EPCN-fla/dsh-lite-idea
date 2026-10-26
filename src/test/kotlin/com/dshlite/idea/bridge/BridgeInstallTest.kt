package com.dshlite.idea.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeInstallTest {
    @Test fun `current hosts select bridge 0_4 and legacy hosts stay on 0_2`() {
        assertEquals("dsh-vscode-bridge@^0.4.0", BridgeInstall.BRIDGE_SPEC_CURRENT)
        for (version in listOf("0.1.7-rc.2", "0.2.0-rc.2", "0.2.1-rc.2", null)) {
            assertEquals(BridgeInstall.BRIDGE_SPEC_CURRENT, BridgeInstall.bridgeSpecForHost(version))
        }
        assertEquals(BridgeInstall.BRIDGE_SPEC_LEGACY, BridgeInstall.bridgeSpecForHost("0.1.5-rc.2"))
        assertEquals(BridgeInstall.BRIDGE_SPEC_LEGACY, BridgeInstall.bridgeSpecForHost("0.1.6-alpha.2"))
    }

    @Test fun `repair accepts a literal package argument without shell interpolation`() {
        val script = BridgeInstall.buildInstallScript("dsh")
        assertTrue(script.contains("PKG_SPEC=\"\$1\""))
        assertTrue(script.contains("dsh plugin --profile acp-vscode add \"\${PKG_SPEC}\""))
        assertFalse(script.contains("__PKG__"))
        assertFalse(script.contains("bridge package already installed"))
    }
}
