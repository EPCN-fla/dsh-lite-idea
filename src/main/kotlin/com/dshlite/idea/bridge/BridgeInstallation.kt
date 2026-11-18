/**
 * Target-side bridge package detection, independent of runtime discovery.
 * Pure script builders/parsers are kept separate so UI integration can be added
 * without making a discovery miss look like proof that the package is absent.
 */
package com.dshlite.idea.bridge

import com.dshlite.idea.launcher.ShellEnv

enum class BridgeInstallationState { INSTALLED, MISSING, UNKNOWN }

data class BridgeSetupPrompt(
    val message: String,
    val actionLabel: String,
)

/**
 * Check both the profile dependency and its installed package manifest.
 * cordis.patch.yml deliberately plays no part: a disabled or broken activation
 * row is a connection problem, not proof that the package is absent.
 */
fun buildBridgeInstallationProbeScript(profile: String, dshHome: String? = null): String {
    val homePrefix = dshHome?.takeIf { it.isNotEmpty() }
        ?.let { "export DSH_HOME=${ShellEnv.shellQuote(it)}\n" }
        .orEmpty()
    return homePrefix + """DSH_HOME="${'$'}{DSH_HOME:-${'$'}HOME/.dsh}"
PROFILE_NAME=${ShellEnv.shellQuote(profile)}
PROFILE="${'$'}DSH_HOME/profiles/${'$'}PROFILE_NAME"
if [ -f "${'$'}PROFILE/node_modules/dsh-vscode-bridge/package.json" ] \
  && grep -Eq '"dsh-vscode-bridge"[[:space:]]*:' "${'$'}PROFILE/package.json" 2>/dev/null; then
  printf 'installed\n'
else
  printf 'missing\n'
fi
"""
}

fun parseBridgeInstallationProbe(stdout: String): BridgeInstallationState =
    when (stdout.trim()) {
        "installed" -> BridgeInstallationState.INSTALLED
        "missing" -> BridgeInstallationState.MISSING
        else -> BridgeInstallationState.UNKNOWN
    }

/** User-facing copy for the three probe outcomes and the two startup contexts. */
fun bridgeSetupPrompt(
    state: BridgeInstallationState,
    startupFailed: Boolean,
    profile: String,
): BridgeSetupPrompt = when (state) {
    BridgeInstallationState.INSTALLED -> BridgeSetupPrompt(
        message = if (startupFailed) {
            "DSH: the bridge package is installed in profile \"$profile\", but dsh failed to start. " +
                "Check cordis.patch.yml and the DSH output, or repair the bridge setup."
        } else {
            "DSH: the bridge package is installed in profile \"$profile\", but no bridge is connected to this workspace. " +
                "Check cordis.patch.yml and restart DSH, or repair the bridge setup."
        },
        actionLabel = "修复 Bridge",
    )

    BridgeInstallationState.MISSING -> BridgeSetupPrompt(
        message = if (startupFailed) {
            "DSH: dsh failed to start and the bridge package was not detected in profile \"$profile\". " +
                "Install the required bridge plugin now?"
        } else {
            "DSH: the dsh-vscode-bridge plugin is required for titles, delete, presets, permission modes " +
                "and workspace grouping. Install it now?"
        },
        actionLabel = "安装 Bridge",
    )

    BridgeInstallationState.UNKNOWN -> BridgeSetupPrompt(
        message = if (startupFailed) {
            "DSH: dsh failed to start and the bridge installation could not be confirmed. " +
                "Check DSH Doctor, or run Install/Repair Bridge Plugin."
        } else {
            "DSH: no bridge is connected to this workspace and the package installation could not be confirmed. " +
                "Run DSH Doctor, or install/repair the bridge plugin."
        },
        actionLabel = "安装/修复 Bridge",
    )
}
