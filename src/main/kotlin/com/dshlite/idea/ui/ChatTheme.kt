package com.dshlite.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

/** Theme-derived colors, resolved at paint time so LAF switches follow. */
object ChatColors {
    fun mix(a: Color, b: Color, t: Double): Color = Color(
        (a.red + (b.red - a.red) * t).toInt(),
        (a.green + (b.green - a.green) * t).toInt(),
        (a.blue + (b.blue - a.blue) * t).toInt(),
    )
    val background: Color get() = UIUtil.getPanelBackground()
    val foreground: Color get() = UIUtil.getLabelForeground()
    val muted: Color get() = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
    val border: Color get() = JBColor.namedColor("Borders.color", JBColor.border())
    /** VS Code `chat.requestBackground`: a faint tint of the foreground. */
    val bubble: Color get() = mix(background, foreground, 0.07)
    val bubbleBorder: Color get() = mix(background, foreground, 0.18)
    val codeBackground: Color get() = mix(background, foreground, 0.06)
    val hover: Color get() = mix(background, foreground, 0.10)
    val success = JBColor(Color(0x2E8B57), Color(0x5FB865))
    val failure = JBColor(Color(0xD03D3D), Color(0xE06C6C))
    val running = JBColor(Color(0xC28A00), Color(0xD9A343))
    val errorBackground = JBColor(Color(0xFDEDED), Color(0x4B2C2C))
    val errorBorder = JBColor(Color(0xE57373), Color(0x8F4545))
}

object ChatFonts {
    /** Chat text (messages + composer) sits this many points above the IDE label font. */
    private const val BODY_STEP = 1.5f

    private val cjkFamily: String? by lazy {
        val have = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
        listOf("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei")
            .firstOrNull { it in have }
    }

    /** The editor font family (e.g. JetBrains Mono); logical MONOSPACED is
     *  Courier New on Windows. Falls back when no application is running. */
    private val monoFamily: String by lazy {
        runCatching {
            com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Font.MONOSPACED
    }

    fun hasCjk(text: String) = text.any { it in '\u4e00'..'\u9fff' || it in '\u3000'..'\u303f' || it in '\uff00'..'\uffef' }

    /** Base chat font: the IDE label font, a step larger (HiDPI-scaled). */
    fun body(): Font {
        val label = UIUtil.getLabelFont()
        return label.deriveFont(label.size2D + com.intellij.util.ui.JBUI.scale(BODY_STEP))
    }

    /** Latin text stays on [base]; CJK text avoids the serif (宋体) fallback. */
    fun forText(text: String, base: Font = body()): Font {
        val family = cjkFamily
        return if (family != null && hasCjk(text)) Font(family, base.style, base.size).deriveFont(base.size2D) else base
    }

    /** Code font, one point under [base]. */
    fun mono(base: Font = body()): Font = Font(monoFamily, Font.PLAIN, 1).deriveFont((base.size2D - 1f).coerceAtLeast(10f))

    /** Secondary text (card headers, labels) relative to the chat body. */
    fun small(base: Font = body(), delta: Float = 1f): Font = base.deriveFont(base.size2D - delta)
}
