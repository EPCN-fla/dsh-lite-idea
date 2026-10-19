/**
 * Slash-command menu model for the composer, ported from dsh-lite-vscode
 * src/webview/slash.ts. dsh exposes no commands over ACP (they live in the
 * TUI/Web interaction layer), so the menu combines native commands from the
 * bridge (command.list), the bridge skill catalog (skill.list), and actions
 * the plugin itself can run.
 */
package com.dshlite.idea.ui

data class SlashCommand(
    /** Command name typed after "/" (e.g. "model"). */
    val name: String,
    /** Display label. */
    val label: String,
    /** One-line description. */
    val description: String,
    /** Menu group: commands vs skills. */
    val section: Section,
    /** local: handled by the plugin; native: run via bridge command.run;
     *  skill: sent as a prompt asking the model to invoke the skill. */
    val run: RunKind,
    /** Native commands only: input hint — picking fills `/name ` for arguments. */
    val hint: String? = null,
) {
    enum class Section { COMMAND, SKILL }
    enum class RunKind { LOCAL, NATIVE, SKILL }
}

/** A manually typed "/name [args]" line resolved against the known commands. */
sealed interface SlashLine {
    data class Native(val line: String) : SlashLine
    data class Local(val cmd: SlashCommand) : SlashLine
    data class Skill(val name: String, val rest: String) : SlashLine
}

object SlashCommands {
    /** Chinese labels/descriptions for known native dsh commands (Web-UI parity). */
    val NATIVE_ZH: Map<String, Pair<String, String>> = mapOf(
        "compact" to ("压缩" to "压缩以上对话内容"),
        "plan" to ("计划" to "进入或退出计划模式"),
        "goal" to ("目标" to "设置或查看长期任务目标"),
        "permission" to ("权限" to "切换权限预设（沙箱模式与审批策略）"),
        "export" to ("下载日志" to "将当前会话内容导出为 ZIP"),
    )

    /** Native commands never offered in the slash menu. */
    val NATIVE_BLOCKLIST: Set<String> = setOf("feedback")

    /** Native commands that fill the composer instead of auto-running. */
    val NATIVE_FILL: Set<String> = setOf("plan", "goal")

    /** Resolve a typed "/name [args]" line; null = send as a normal prompt. */
    fun resolve(commands: List<SlashCommand>, input: String): SlashLine? {
        val m = Regex("""^/(\S+)([\s\S]*)$""").find(input.trim()) ?: return null
        val name = m.groupValues[1].lowercase()
        val rest = m.groupValues[2].trim()
        val cmd = commands.find { it.name.lowercase() == name } ?: return null
        return when (cmd.run) {
            SlashCommand.RunKind.NATIVE ->
                SlashLine.Native("/${cmd.name}${if (rest.isNotEmpty()) " $rest" else ""}")
            SlashCommand.RunKind.SKILL -> SlashLine.Skill(cmd.name, rest)
            SlashCommand.RunKind.LOCAL ->
                // local commands only intercept the bare "/name" form
                if (rest.isEmpty()) SlashLine.Local(cmd) else null
        }
    }

    /** Filter by the text typed so far: matches the name or the label. */
    fun filter(commands: List<SlashCommand>, query: String): List<SlashCommand> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return commands
        return commands.filter {
            it.name.lowercase().contains(q) || it.label.contains(query.trim())
        }
    }
}
