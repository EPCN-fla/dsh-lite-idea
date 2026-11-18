# DSH Lite — DeepSeek Harness for JetBrains IDEs

Chat with the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) agent inside
IntelliJ IDEA / PyCharm / WebStorm / GoLand / …: sessions, streaming replies, tool-call cards,
permission prompts, model/effort/preset/permission pickers, slash commands, and a skill catalog —
built on the standard [Agent Client Protocol](https://agentclientprotocol.com) (ACP), like the
Claude Code and Codex integrations. Sibling of [dsh-lite-vscode](https://github.com/EPCN-fla/dsh-lite-vscode).

## Features

- **Chat tool window** (pure Swing) with GFM tables/strikethrough, collapsible thoughts and tool cards,
  themed code blocks, copy icons, and larger consistent text in the transcript and composer
- **Session management** — ACP-paginated list / resume / rename / delete (rename & delete need the bridge plugin);
  restored transcripts include reasoning and tool results, and the history dialog preselects the current session
- **Read-only subagent sessions** — browse direct children of the active main session, including live/stored
  state, and open their transcripts without ACP resume, closing the main session, or changing its composer
- **Model, reasoning-effort, agent-preset, and permission-mode pickers** in the config bar
- **Slash command palette** — typing `/` opens a filtered picker over local actions
  (`/new`, `/model`, `/effort`, `/permission`, `/export`), native dsh commands, and skills
- **Skill catalog** — browse the bridge skill list, insert `/<skill>` into the composer
- **Session log export** as ZIP, with reveal-in-folder / copy-path actions
- **Profile configuration import** — choose any source profile in the same DSH_HOME, then preview and
  explicitly import models and agent presets into the active ACP profile (bridge ≥ 0.4.0 for the connected command)
- **Permission prompts** as modal dialogs; **cancel** interrupts a running turn
- **Change review** — per-session created/modified file lists combine VFS, ACP diff and final Git status;
  expand the bar above the composer to open a HEAD/current diff without losing pre-existing workspace dirt
- **Plan / todo rendering** and a live context-usage meter
- Cross-environment: run dsh in WSL, natively on Windows, or same-side on Linux/macOS
  (see [Topologies](#topologies))

## Requirements

- **JetBrains IDE based on IntelliJ Platform 2024.3 / build 243 or newer** (including Android Studio builds that meet this baseline)
- **Node.js ≥ 22.19** on the side where dsh runs (nvm/volta/mise/asdf are picked up automatically)
- **DeepSeek Harness** — installed (`npm i -g @deepseek-ai/dsh`), used via `npx`, or from a repository checkout
- **dsh-vscode-bridge** dsh plugin (**required** for titles, delete, presets, permission modes,
  workspace grouping, native slash commands, skill catalog, session export, subagent catalogs, and profile configuration import) — the toolbar
  **Install Bridge** button wires it in (creates the default `acp-vscode` profile, registers the
  service rows, installs the plugin). Profile `acp` runs bridge-less with chat-only features.

### Version matching

| dsh-lite-idea | DeepSeek Harness | dsh-vscode-bridge |
|---|---|---|
| **0.2.0** | Tested 0.1.5 / 0.1.7 / 0.2.0 / 0.2.1-rc.x / 0.2.2-rc.1–rc.3 cohorts | Selected exactly from published compatibility metadata; subagent sessions require `sessionOrigin` + `sessionExport` (bridge 0.5.1+) |
| **0.1.0 – 0.1.1** | 0.1.5–0.2.1-rc.x | 0.1.3–0.4.x, using the former fixed host mapping |

Install/Repair first detects the host dsh version, then queries the configured npm registry for both
modern `dshCompatibility` matrices and legacy development-dependency ranges. It picks the highest
compatible SemVer, preferring stable releases, and never falls back to a guessed package line.
Unknown hosts, malformed/unavailable registry responses and no-compatible results stop **before**
any profile mutation. Repair always forwards the selected exact package spec so upgrades are not skipped.

The installer writes only the ACP bundle plus the stable `workspace` and `dsh-vscode-bridge` activation
rows. It no longer snapshots preset/model/permission packages into `cordis.patch.yml`; after installation,
the source-profile import flow supplies those user-selected rows. Node.js 22.19 remains the baseline,
but a registry-selected bridge may require a newer runtime (bridge 0.5.x requires Node.js 24.9+).

## Quick start

1. Once the plugin is approved on JetBrains Marketplace, open *Settings → Plugins → Marketplace*,
   search for **DSH Lite**, and install it. Before the first approval, download the
   `plugin-distribution` artifact from a successful GitHub Actions run and extract the plugin ZIP,
   then open *Settings → Plugins → ⚙ → Install Plugin from Disk*. For a local build, use
   `build/distributions/dsh-lite-idea-*.zip`; developers can use `./gradlew runIde` for a sandbox.
2. Open *Settings → Tools → DSH Lite*: pick where dsh runs and how to launch it.
3. Click **Install Bridge** in the DSH tool window: detects the host, installs the latest compatible
   bridge and creates/repairs the default `acp-vscode` profile with only stable activation rows.
   Then choose a source profile (default `web`) and review the model/preset import preview.
4. Open the **DSH** tool window on the right — press **+** or just type a prompt.

## Settings

| Setting | Default | Description |
|---|---|---|
| `Runtime` | `auto` | Where the agent process runs: `auto` (WSL on Windows, same-side elsewhere), `wsl`, or `windows` |
| `dsh command` | `npx -y @deepseek-ai/dsh` | Launch command on the target side; the plugin appends `--profile <profile>` |
| `dsh profile` | `acp-vscode` | dsh profile to boot; the default is created by the bridge installer. `acp` runs bridge-less |
| `WSL distro` | `""` | WSL distribution name when runtime is `wsl` (empty = default) |
| `Extra environment` | `""` | `KEY=VALUE` per line, passed to the dsh process. Stored as plain text in `dsh-lite.xml`; do not put API keys or other secrets here |

## Topologies

| IDE host | dsh runs | Setting |
|---|---|---|
| Windows | WSL | `auto` / `wsl` (primary) |
| Windows | Windows | `windows` (native `cmd.exe`; the bridge installer is POSIX-only) |
| Linux / macOS | same side | any (always local) |
| JetBrains remote backend in WSL | same WSL | any (always local — WSL↔WSL) |

Windows drive and WSL UNC paths are translated directly to dsh paths without spawning a process.
WSL-native export paths map back through `wslpath` to `\\wsl.localhost\<distro>\…` UNC. On Windows,
bridge discovery files are read with a target-side WSL shell command; POSIX hosts read them directly.
Discovery backs off when no bridge is found. TCP connects to `127.0.0.1` (WSL2 loopback forwarding).

## Toolbar actions

| Action | What it does |
|---|---|
| **＋ New Session** | Start a fresh session |
| **🕘 Sessions** | List / resume / rename / delete past root sessions for this workspace |
| **Subagents** | Refresh and open a direct child of the current main session as a read-only transcript |
| **★ Skills** | Browse the bridge skill catalog; pick one to insert `/<name>` into the composer |
| **⤴ Export** | Export the current session log as ZIP (bridge `session.exportZip`) |
| **Import Models and Presets from Profile** | Choose a source profile, preview and import into the connected ACP profile (bridge ≥ 0.4.0) |
| **⟳ Reconnect** | Restart the dsh ACP process |
| **⬇ Install Bridge** | Idempotent bridge installer/repairer on the target side |
| **ⓘ Doctor** | Probe node/dsh availability **on the dsh side**, and report the bridge connection with its host dsh version |

## Import Profile configuration

Run **Import Models and Presets from Profile** in the DSH tool-window gear menu. Enter any source
profile directory name in the same DSH_HOME (default `web`); paths and reserved names are rejected,
and a missing source stops before preview. The normal connected command requires bridge `configSync`
(0.4.0+), while the post-install flow runs on the DSH host and therefore works before the new bridge
has reconnected.

Choose models, presets, or both, then whether same-name target entries are kept (default) or replaced.
The host-side preview shows only identifiers: source/target profiles, providers, presets, supporting
service rows and skipped entries. Configuration bytes and credentials stay on the DSH host.

Cancellation and an unchanged preview write nothing. Import uses the exact reviewed source/options/
revision, with host locking, backups and atomic replacement; stale previews, timeouts and disconnects
are never retried automatically. A write timeout has an uncertain result, so inspect the target profile
and backups before previewing again. After a successful import, finish active turns and use **Reconnect**.
Existing sessions are not switched automatically. Environment variables, OAuth/credential stores and
third-party packages are not copied.

## Troubleshooting

`idea.log` (Help → Show Log) records connection lifecycle, session selection/deletion, history-load
summaries, installation results and errors. UI activity, model defaults, subprocess output dumps and
raw discovery JSON are not logged. Unexpected agent exits include a bounded stderr tail; Doctor
remains available for on-demand diagnostics.

- **Chat stuck on "starting…"** → run Doctor. Common causes: nvm-managed node not on the
  non-interactive PATH (the plugin sources nvm/volta/mise/asdf quietly; otherwise use an absolute
  node path in `dsh command`), wrong distro, or a bad profile.
- **First `npx` run times out** → it downloads the package; install once (`npm i -g @deepseek-ai/dsh`)
  or point `dsh command` at a checkout (e.g. `node ~/deepseek-harness/apps/cli/lib/bin.js`).
- **"no API key for provider route"** → configure the key once in the dsh Web UI Models page
  (shared via `$DSH_HOME`). Extra environment is plain-text IDE state and is not
  a credential store; keep API keys in the dsh provider configuration instead.
- **Bridge features missing** (titles/delete/presets/permissions/slash commands) → run the bridge
  installer; see the [version table](#version-matching).
- **"profile "acp-vscode" does not exist"** → the default profile is created by the bridge
  installer; click Install Bridge.
- **Custom models gone after a dsh 0.1.7 upgrade** → 0.1.7 made model providers per-profile;
  use **Import Models and Presets from Profile**, or copy the `llm-pi-ai` section from
  `settings.yaml.imported` into the profile's `cordis.patch.yml`.

## Development

```sh
./gradlew test           # unit/regression tests (no live dsh or API credentials needed)
./gradlew buildPlugin    # produces build/distributions/dsh-lite-idea-<version>.zip
./gradlew runIde         # sandbox IDE with the plugin installed
```

A JDK 21 is required. Set `JAVA_HOME` to your JDK and add its `bin` directory to `PATH`.
Machine-specific `org.gradle.java.home` overrides belong in your user-level
`~/.gradle/gradle.properties`, not in this repository.

```
src/main/kotlin/com/dshlite/idea/
  acp/       ACP wire models + ndjson JSON-RPC client + per-project AcpService
  bridge/    bridge TCP client + discovery + registry install + host-side profile import
  chat/      ACP-first catalogs, subagent classification, transcript cache/import and change tracking
  launcher/  WSL/Windows/Local process launch + path mapping + shell bootstrap
  settings/  Persistent settings + configurable page
  ui/        ChatPanel (Swing), slash palette, skills/sessions dialogs, permission broker, markdown view
src/main/resources/bridge/  minimal install template + generated host-side profile-import helper
```

## Publishing

Marketplace [Overview](docs/overview.html) and [What's New](docs/whats-new.html) are maintained as
HTML and embedded into the plugin metadata during the build.

CI validates pull requests on Linux, Windows and macOS, including IDE compatibility verification
on Linux. Pushing a stable `v<pluginVersion>` tag runs the same checks, then publishes the verified
ZIP to JetBrains Marketplace when `JETBRAINS_PUBLISH=true` and the `JETBRAINS_MARKETPLACE_TOKEN`
secret are configured. Prerelease tags run validation only. Plugin ZIPs and reports are available
as GitHub Actions artifacts.

For the first release, leave `JETBRAINS_PUBLISH` unset. Download and extract the
`plugin-distribution` artifact from a successful Actions run, then manually upload the plugin ZIP
to [JetBrains Marketplace](https://plugins.jetbrains.com/) to create the plugin listing.
Select the MIT license. The Gradle publishing task uploads updates to an existing listing.

For subsequent releases, configure the following under **Settings → Secrets and variables → Actions**:

| Type | Name | Value |
|---|---|---|
| Secret | `JETBRAINS_MARKETPLACE_TOKEN` | [Marketplace permanent token](https://plugins.jetbrains.com/docs/marketplace/marketplace-token.html) with permission to update this plugin |
| Variable | `JETBRAINS_PUBLISH` | `true` |

Update `pluginVersion` in `gradle.properties` and the matching `<h2>version — title</h2>` heading
in What's New, commit the changes, and push a matching tag (for example, `v0.2.0`).
Enable automatic publishing starting with the version after the initial manual upload to avoid
uploading the same version twice. Marketplace uploads remain subject to JetBrains review.

Run `./gradlew check buildPlugin verifyPluginStructure verifyPlugin` to validate locally.
Plugin Verifier currently checks IntelliJ IDEA Community 2024.3.6; additional IDEs can be configured
in `pluginVerification.ides`. A failed Marketplace job can be rerun using the verified CI artifact.

## License

[MIT License](LICENSE) — Copyright (c) 2026 dsh-lite-idea contributors.
