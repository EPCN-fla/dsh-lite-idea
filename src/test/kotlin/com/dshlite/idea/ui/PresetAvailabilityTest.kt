package com.dshlite.idea.ui

import org.junit.Assert.*
import org.junit.Test

class PresetAvailabilityTest {
    @Test fun `legacy bridge keeps cordis disabled`() {
        assertNotNull(effectivePresetBrokenReason("cordis", configSync = false, nativeBroken = null, failedPreviously = false))
        assertNull(effectivePresetBrokenReason("standard", configSync = false, nativeBroken = null, failedPreviously = false))
    }

    @Test fun `config sync bridge trusts native and learned availability`() {
        assertNull(effectivePresetBrokenReason("cordis", configSync = true, nativeBroken = null, failedPreviously = false))
        assertEquals("missing dependency", effectivePresetBrokenReason("cordis", true, "missing dependency", false))
        assertEquals("failed to mount previously", effectivePresetBrokenReason("custom", true, null, true))
    }
}
