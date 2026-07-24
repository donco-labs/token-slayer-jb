package com.tokenslayer.ui

import com.intellij.openapi.util.IconLoader

/**
 * Icon registry for TokenSlayer. Referenced by plugin.xml (e.g. the tool window icon).
 *
 * This class was referenced from plugin.xml but never defined, which caused the tool
 * window's icon to fail to resolve at load time. Icons are loaded lazily from the
 * plugin's resources via [IconLoader].
 */
object TokenSlayerIcons {
    /** 13×13 tool-window icon (light/dark aware via IconLoader). */
    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/tokenslayer_toolwindow.svg", TokenSlayerIcons::class.java)
}
