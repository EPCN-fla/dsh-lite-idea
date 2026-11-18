package com.dshlite.idea.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeInstallTest {
    @Test fun `repair accepts a literal package argument without shell interpolation`() {
        val script = BridgeInstall.buildInstallScript("dsh")
        assertTrue(script.contains("PKG_SPEC=\"\$1\""))
        assertTrue(script.contains("dsh plugin --profile acp-vscode add \"\${PKG_SPEC}\""))
        assertFalse(script.contains("__PKG__"))
        assertFalse(script.contains("bridge package already installed"))
    }

    @Test fun `installer owns only stable rows and installs package before editing activation`() {
        val script = BridgeInstall.buildInstallScript("dsh")
        assertTrue(script.contains("name: '@deepseek-ai/dsh-workspace'"))
        assertTrue(script.contains("name: 'dsh-vscode-bridge'"))
        assertFalse(script.contains("dsh-agent-preset"))
        assertFalse(script.contains("permission-preset"))
        assertFalse(script.contains("__PATCH_"))
        assertTrue(script.indexOf("plugin --profile acp-vscode add") < script.indexOf("PYML="))
        assertTrue(script.contains("mktemp \"\$PROF/.cordis.patch.yml.bridge-install.XXXXXX\""))
    }

    @Test fun `registry query requests modern and legacy compatibility metadata`() {
        val script = BridgeInstall.buildBridgeRegistryQueryScript()
        assertTrue(script.contains("dshCompatibility"))
        assertTrue(script.contains("devDependencies.@deepseek-ai/dsh-agent"))
        assertTrue(script.contains("--fetch-timeout=15000"))
    }
}
