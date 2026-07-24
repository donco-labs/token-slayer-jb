package com.tokenslayer.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Theme-aware palette for TokenSlayer UI.
 *
 * NOTE ON [JBColor]: the constructor is `JBColor(light, dark)` — the *first* argument is the
 * light-theme colour. Earlier code passed the same Catppuccin **Mocha** (dark) value for both
 * arguments, so light-theme users got the dark palette. Each entry below therefore pairs a
 * Catppuccin **Latte** (light) value with its Mocha (dark) counterpart.
 *
 * Structural colours (panel/card background, borders, body text) deliberately defer to the
 * IDE's own theme rather than hard-coding a background, so the tool window matches whatever
 * theme — including custom ones — the user actually has active.
 */
object TokenSlayerColors {
    // ── Accents (Latte → Mocha) ──────────────────────────────────────────────
    val GREEN = JBColor(Color(0x40A02B), Color(0xA6E3A1))
    val SKY = JBColor(Color(0x0797C4), Color(0x89DCEB))
    val YELLOW = JBColor(Color(0xBF8100), Color(0xF9E2AF))
    val MAUVE = JBColor(Color(0x8839EF), Color(0xCBA6F7))
    val RED = JBColor(Color(0xD20F39), Color(0xF38BA8))

    // ── Structural ───────────────────────────────────────────────────────────

    /** Panel background — inherits the active IDE theme. */
    val panelBackground: Color get() = UIUtil.getPanelBackground()

    /** Slightly inset background for stat cards. */
    val cardBackground: Color get() = JBColor(Color(0xE6E9EF), Color(0x181825))

    /** Card / separator border. */
    val border = JBColor(Color(0xCCD0DA), Color(0x313244))

    /** Primary body text — inherits the active IDE theme. */
    val text: Color get() = JBColor.foreground()

    /** Secondary label text. */
    val subtext = JBColor(Color(0x5C5F77), Color(0xBAC2DE))

    /** Dimmed / placeholder text. */
    val dim = JBColor(Color(0x8C8FA1), Color(0x6C7086))

    /** Progress-bar track. */
    val track = JBColor(Color(0xDCE0E8), Color(0x313244))
}
