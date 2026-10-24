package com.dshlite.idea.ui

private val hostIncompatiblePresets = mapOf(
    "cordis" to "Requires the Web/Desktop client plane (Cordis plugin runtime); unavailable in headless ACP sessions.",
)

/** Config sync can import Cordis host support rows; then trust native mount results. */
internal fun effectivePresetBrokenReason(
    id: String,
    configSync: Boolean,
    nativeBroken: String?,
    failedPreviously: Boolean,
): String? = (if (configSync) null else hostIncompatiblePresets[id])
    ?: nativeBroken
    ?: if (failedPreviously) "failed to mount previously" else null
