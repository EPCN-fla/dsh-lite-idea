package com.dshlite.idea.ui

import org.junit.Assert.*
import org.junit.Test

class PresetAvailabilityTest {
    @Test fun `legacy bridge keeps cordis disabled`() {
        assertNotNull(effectivePresetBrokenReason("cordis", hostSupportImported = false, nativeBroken = null, failedPreviously = false))
        assertNull(effectivePresetBrokenReason("standard", hostSupportImported = false, nativeBroken = null, failedPreviously = false))
    }

    @Test fun `successful host support import trusts native and learned availability`() {
        assertNull(effectivePresetBrokenReason("cordis", hostSupportImported = true, nativeBroken = null, failedPreviously = false))
        assertEquals("missing dependency", effectivePresetBrokenReason("cordis", true, "missing dependency", false))
        assertEquals("failed to mount previously", effectivePresetBrokenReason("custom", true, null, true))
    }
}
