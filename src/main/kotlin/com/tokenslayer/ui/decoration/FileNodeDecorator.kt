package com.tokenslayer.ui.decoration

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.packageDependencies.ui.PackageDependenciesNode
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.settings.TokenSlayerSettings
import java.awt.Color

/**
 * Adds color-coded badges to files in the Project tree:
 *   🟢 ⚡ 95%  — high reduction
 *   🟡 ⚡ 60%  — medium reduction
 *   🔴 🛡️ SECRET — excluded file
 *
 * JetBrains equivalent of VS Code's FileDecorationProvider.
 */
class FileNodeDecorator : ProjectViewNodeDecorator {

    private val cache get() = CacheManager.getInstance()
    private val tsService get() = TokenSlayerService.getInstance()
    private val settings get() = TokenSlayerSettings.getInstance()

    override fun decorate(node: ProjectViewNode<*>, data: com.intellij.ide.projectView.PresentationData) {
        if (!settings.enableFileDecorations) return

        val virtualFile = node.virtualFile ?: return
        if (virtualFile.isDirectory) return

        val filePath = virtualFile.path

        // Check if it's an excluded (secrets) file
        val isExcluded = tsService.excludedFiles.any { it.filePath == filePath }
        if (isExcluded) {
            data.addText("  🛡️", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xF38BA8)))
            return
        }

        val entry = cache.allEntries().firstOrNull { it.filePath == filePath } ?: return

        val (badge, color) = when {
            entry.reductionPct >= 70 -> "⚡${entry.reductionPct}%" to Color(0xA6E3A1)
            entry.reductionPct >= 40 -> "⚡${entry.reductionPct}%" to Color(0xF9E2AF)
            else -> return // don't decorate low-reduction files
        }

        data.addText("  $badge", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color))
        data.tooltip = "TokenSlayer: ${entry.reductionPct}% token reduction (${entry.originalTokens} → ${entry.skeletonTokens} tokens)"
    }

    override fun decorate(node: PackageDependenciesNode, cellRenderer: ColoredTreeCellRenderer) {
        // Not used
    }
}
