package com.dshlite.idea.ui

private val hostIncompatiblePresets = mapOf(
    "cordis" to "Requires the Web/Desktop client plane (Cordis plugin runtime); unavailable in headless ACP sessions.",
)

/** Only a successful Cordis support import removes the legacy headless restriction. */
internal fun effectivePresetBrokenReason(
    id: String,
    hostSupportImported: Boolean,
    nativeBroken: String?,
    failedPreviously: Boolean,
): String? = (if (hostSupportImported) null else hostIncompatiblePresets[id])
    ?: nativeBroken
    ?: if (failedPreviously) "failed to mount previously" else null
