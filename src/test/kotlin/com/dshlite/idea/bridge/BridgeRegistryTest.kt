package com.dshlite.idea.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRegistryTest {
    private val entries = BridgeRegistry.parseBridgeRegistryResponse(
        """
        [
          {"version":"0.1.1","devDependencies.@deepseek-ai/dsh-agent":"0.1.5-rc.2"},
          {"version":"0.1.2","devDependencies.@deepseek-ai/dsh-agent":"^0.1.5-rc.2"},
          {"version":"0.1.3","devDependencies.@deepseek-ai/dsh-agent":"0.1.5-rc.2"},
          {"version":"0.2.0","devDependencies.@deepseek-ai/dsh-agent":"0.1.7-rc.1"},
          {"version":"0.4.0","dshCompatibility":{"0.1.7-rc.1":{},"0.2.0-rc.1":{},"0.2.1-rc.2":{}}},
          {"version":"0.4.2","dshCompatibility":{"0.1.7-rc.1":{},"0.2.0-rc.1":{},"0.2.0-rc.2":{},"0.2.1-rc.2":{},"0.2.1-rc.5":{},"0.2.2-rc.1":{}}},
          {"version":"0.5.0","dshCompatibility":{"0.2.2-rc.2":{}}},
          {"version":"0.5.1","dshCompatibility":{"0.2.2-rc.2":{}}},
          {"version":"0.5.2","dshCompatibility":{"0.2.2-rc.2":{},"0.2.2-rc.3":{}}},
          {"version":"0.5.3-alpha.1","dshCompatibility":{"0.2.2-rc.3":{}}},
          {"version":"0.5.3","dshCompatibility":{"0.2.2-rc.4":{}}}
        ]
        """.trimIndent(),
    )

    private fun version(text: String): DshVersion = BridgeRegistry.parseDshVersion(text)!!

    private fun select(host: String): String =
        BridgeRegistry.selectLatestCompatibleBridge(version(host), entries)

    private fun compatibility(vararg hosts: String): Map<String, Any?> = hosts.associateWith { null }

    private fun selectLegacy(host: String, spec: String): String =
        BridgeRegistry.selectLatestCompatibleBridge(
            version(host),
            listOf(BridgeRegistryEntry("0.6.0", devDependencyDshAgent = spec)),
        )

    private fun assertRegistryError(code: String, block: () -> Unit) {
        val error = assertThrows(BridgeRegistryError::class.java) { block() }
        assertEquals(code, error.code)
    }

    @Test
    fun `online selection picks highest compatible version not pinned historical mapping`() {
        assertEquals("dsh-vscode-bridge@0.5.2", select("0.2.2-rc.2"))
        assertEquals("dsh-vscode-bridge@0.5.2", select("0.2.2-rc.3"))
        assertEquals("dsh-vscode-bridge@0.4.2", select("0.2.2-rc.1"))
        assertEquals("dsh-vscode-bridge@0.4.2", select("0.2.1-rc.2"))
    }

    @Test
    fun `stable compatible releases outrank prereleases and incompatible newer releases`() {
        assertEquals("dsh-vscode-bridge@0.5.2", select("0.2.2-rc.3"))
        assertEquals("dsh-vscode-bridge@0.5.3", select("0.2.2-rc.4"))
        assertEquals(
            "dsh-vscode-bridge@0.6.0-alpha.1",
            BridgeRegistry.selectLatestCompatibleBridge(
                version("0.2.2-rc.4"),
                listOf(BridgeRegistryEntry("0.6.0-alpha.1", compatibility("0.2.2-rc.4"))),
            ),
        )
    }

    @Test
    fun `legacy development dependency metadata supports exact and caret ranges`() {
        assertEquals("dsh-vscode-bridge@0.1.3", select("0.1.5-rc.2"))
        assertEquals("dsh-vscode-bridge@0.1.2", select("0.1.6"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { select("0.1.6-alpha.2") }
        assertEquals("dsh-vscode-bridge@0.1.2", select("0.1.5-rc.3"))
        assertEquals("dsh-vscode-bridge@0.4.2", select("0.1.7-rc.1"))
    }

    @Test
    fun `verified stable hosts use tested RC cohort and unknown stable versions stay closed`() {
        assertEquals("dsh-vscode-bridge@0.4.2", select("0.1.7"))
        assertEquals("dsh-vscode-bridge@0.4.2", select("0.2.0"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { select("0.2.2") }
    }

    @Test
    fun `no compatible release and malformed registry responses fail closed`() {
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { select("0.3.0") }
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            BridgeRegistry.selectLatestCompatibleBridge(
                version("0.2.2-rc.3"),
                listOf(
                    BridgeRegistryEntry(
                        "0.6.0",
                        dshCompatibility = emptyMap(),
                        devDependencyDshAgent = "0.2.2-rc.3",
                    ),
                ),
            )
        }
        for (malformed in listOf("not json", "NaN", "01", "true false", "{version:'0.1.0'}", "[{\"version\":\"0.1.0\",}]")) {
            assertRegistryError(BridgeRegistry.REGISTRY_INVALID) {
                BridgeRegistry.parseBridgeRegistryResponse(malformed)
            }
        }
        assertEquals(emptyList<BridgeRegistryEntry>(), BridgeRegistry.parseBridgeRegistryResponse("[{\"version\":\"not a version\"}]"))
    }

    @Test
    fun `SemVer comparison handles release and numeric prerelease precedence`() {
        assertTrue(BridgeRegistry.compareDshVersions(version("0.5.2"), version("0.5.2-rc.10")) > 0)
        assertTrue(BridgeRegistry.compareDshVersions(version("0.5.2-rc.10"), version("0.5.2-rc.2")) > 0)
        assertTrue(BridgeRegistry.compareDshVersions(version("0.5.2-rc.1"), version("0.5.2-alpha.9")) > 0)
        assertEquals(0, BridgeRegistry.compareDshVersions(version("0.5.2-rc.1"), version("0.5.2-rc.1")))
        assertTrue(BridgeRegistry.compareDshVersions(version("1.0.0-rc.1"), version("1.0.0-rc.1.1")) < 0)
        assertTrue(BridgeRegistry.compareDshVersions(version("1.0.0-1"), version("1.0.0-alpha")) < 0)
    }

    @Test
    fun `host parser extracts first version and tested cohort remains explicit`() {
        assertEquals(DshVersion(0, 2, 0, "rc.2"), BridgeRegistry.parseDshVersion("dsh version 0.2.0-rc.2 (linux)"))
        assertEquals("0.2.0-rc.2", BridgeRegistry.formatDshVersion(version("0.2.0-rc.2")))
        assertNull(BridgeRegistry.parseDshVersion("dsh 0.2"))
        assertNull(BridgeRegistry.parseDshVersion(null))
        assertTrue(BridgeRegistry.isTestedHostVersion(version("0.2.1-rc.99")))
        assertFalse(BridgeRegistry.isTestedHostVersion(version("0.2.1")))
        assertTrue(BridgeRegistry.isTestedHostVersion(version("0.2.2-rc.3")))
        assertFalse(BridgeRegistry.isTestedHostVersion(version("0.2.2-rc.4")))
        assertFalse(BridgeRegistry.isTestedHostVersion(version("0.2.2")))
    }

    @Test
    fun `registry parser accepts one object and filters malformed rows without coercion`() {
        val single = BridgeRegistry.parseBridgeRegistryResponse(
            """{"version":"0.5.2","dshCompatibility":{"0.2.2-rc.2":{}},"devDependencies.@deepseek-ai/dsh-agent":"ignored"}""",
        )
        assertEquals(1, single.size)
        assertEquals("0.5.2", single[0].version)
        assertEquals(setOf("0.2.2-rc.2"), single[0].dshCompatibility?.keys)
        assertEquals("ignored", single[0].devDependencyDshAgent)

        val filtered = BridgeRegistry.parseBridgeRegistryResponse(
            """[null,[],{"version":1},{"version":"1.2.3+build"},{"version":"1.2.3","devDependencies.@deepseek-ai/dsh-agent":7}]""",
        )
        assertEquals(1, filtered.size)
        assertEquals("1.2.3", filtered[0].version)
        assertNull(filtered[0].devDependencyDshAgent)
    }

    @Test
    fun `legacy tilde ranges use node SemVer prerelease behavior`() {
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.9", "~0.1.5"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { selectLegacy("0.2.0", "~0.1.5") }
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.5-rc.3", "~0.1.5-rc.2"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            selectLegacy("0.1.6-alpha.2", "~0.1.5-rc.2")
        }
    }

    @Test
    fun `legacy comparator and OR ranges retain prerelease gating`() {
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.8", ">=0.1.5 <0.2.0"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            selectLegacy("0.1.6-alpha.2", ">=0.1.5 <0.2.0")
        }
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.5-rc.3", ">0.1.5-rc.2"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            selectLegacy("0.1.6-alpha.2", ">0.1.5-rc.2")
        }
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.2.2-rc.3", "0.1.5 || 0.2.2-rc.3"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            selectLegacy("0.2.2-rc.4", "0.1.5 || 0.2.2-rc.3")
        }
    }

    @Test
    fun `legacy partial wildcard and hyphen ranges are supported`() {
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.9", "^0.1"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { selectLegacy("0.2.0", "^0.1") }
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("1.2.9", "1.2.x"))
        assertEquals("dsh-vscode-bridge@0.6.0", selectLegacy("0.1.6", "0.1.5 - 0.1.7"))
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { selectLegacy("0.1.8", "0.1.5 - 0.1.7") }
    }

    @Test
    fun `invalid legacy ranges and absent compatibility metadata fail closed`() {
        for (spec in listOf(
                "", "not-a-range", "^", ">>0.1.5", "0.1.5 ||", "0.1.5 || garbage",
                "^01.1.5", "^0.1.5-rc.01", "~0.1.5-alpha..1",
            )) {
            assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) { selectLegacy("0.1.6", spec) }
        }
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            BridgeRegistry.selectLatestCompatibleBridge(
                version("0.1.5"),
                listOf(BridgeRegistryEntry("0.6.0")),
            )
        }
    }

    @Test
    fun `malformed explicit compatibility cannot reopen legacy fallback`() {
        val parsed = BridgeRegistry.parseBridgeRegistryResponse(
            """{"version":"0.6.0","dshCompatibility":null,"devDependencies.@deepseek-ai/dsh-agent":"0.2.2-rc.3"}""",
        )
        assertRegistryError(BridgeRegistry.REGISTRY_NO_COMPATIBLE) {
            BridgeRegistry.selectLatestCompatibleBridge(version("0.2.2-rc.3"), parsed)
        }
    }

    @Test
    fun `manually supplied invalid compatible bridge version is rejected`() {
        assertRegistryError(BridgeRegistry.REGISTRY_INVALID) {
            BridgeRegistry.selectLatestCompatibleBridge(
                version("0.2.2-rc.3"),
                listOf(BridgeRegistryEntry("not a version", compatibility("0.2.2-rc.3"))),
            )
        }
    }

    @Test
    fun `convenience API parses host and registry and rejects invalid host`() {
        val json = """{"version":"0.6.0","dshCompatibility":{"0.2.2-rc.3":{}}}"""
        assertEquals(
            "dsh-vscode-bridge@0.6.0",
            BridgeRegistry.selectLatestCompatibleBridge("dsh 0.2.2-rc.3", json),
        )
        assertRegistryError(BridgeRegistry.REGISTRY_INVALID) {
            BridgeRegistry.selectLatestCompatibleBridge("unknown", json)
        }
    }
}
