package com.tokenslayer.ui.decoration

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ui.SimpleTextAttributes
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.settings.TokenSlayerSettings
import com.tokenslayer.ui.TokenSlayerColors

/**
 * Adds color-coded badges to files in the Project tree:
 *   🟢 ⚡ 95%  — high reduction
 *   🟡 ⚡ 60%  — medium reduction
 *   🔴 🛡️ SECRET — excluded file
 *
 * JetBrains equivalent of VS Code's FileDecorationProvider.
 */
class FileNodeDecorator : ProjectViewNodeDecorator {
    private val settings get() = TokenSlayerSettings.getInstance()

    override fun decorate(
        node: ProjectViewNode<*>,
        data: com.intellij.ide.projectView.PresentationData,
    ) {
        if (!settings.enableFileDecorations) return

        val virtualFile = node.virtualFile ?: return
        if (virtualFile.isDirectory) return

        val filePath = virtualFile.path

        // Per-project services: a node belongs to exactly one project, so decorate from that
        // project's cache only. Using the app-level singletons here previously leaked badges
        // between open workspaces.
        val project = node.project ?: return
        val cache = CacheManager.getInstance(project)
        val tsService = TokenSlayerService.getInstance(project)

        // Check if it's an excluded (secrets) file
        val isExcluded = tsService.excludedFiles.any { it.filePath == filePath }
        if (isExcluded) {
            seedFileName(node, data)
            data.addText("  🛡️", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TokenSlayerColors.RED))
            return
        }

        val entry = cache.allEntries().firstOrNull { it.filePath == filePath } ?: return

        val (badge, color) =
            when {
                entry.reductionPct >= 70 -> "⚡${entry.reductionPct}%" to TokenSlayerColors.GREEN
                entry.reductionPct >= 40 -> "⚡${entry.reductionPct}%" to TokenSlayerColors.YELLOW
                else -> return // don't decorate low-reduction files
            }

        seedFileName(node, data)
        data.addText("  $badge", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color))
        data.tooltip = "TokenSlayer: ${entry.reductionPct}% token reduction (${entry.originalTokens} → ${entry.skeletonTokens} tokens)"
    }

    /**
     * PresentationData renders the plain [PresentationData.getPresentableText] only while the
     * colored-fragment list is empty; as soon as a fragment exists the renderer uses the
     * fragments alone. Appending a badge without first re-adding the file name therefore makes
     * the name vanish, leaving just the badge. Seed the name so the badge appends to it.
     */
    private fun seedFileName(
        node: ProjectViewNode<*>,
        data: com.intellij.ide.projectView.PresentationData,
    ) {
        if (data.coloredText.isNotEmpty()) return
        val name = data.presentableText ?: node.virtualFile?.name ?: return
        data.addText(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}
