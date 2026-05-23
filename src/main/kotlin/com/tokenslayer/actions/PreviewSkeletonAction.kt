package com.tokenslayer.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.tokenslayer.services.TokenSlayerService

/**
 * Opens a side-by-side diff view comparing original file vs structural skeleton.
 * JetBrains equivalent of VS Code's "TokenSlayer: Preview Skeleton" command.
 */
class PreviewSkeletonAction : AnAction("Preview Skeleton"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }

        val tsService = TokenSlayerService.getInstance()
        val skeleton =
            tsService.getCachedSkeleton(file.path)
                ?: run {
                    val result = tsService.analyzeFile(file, project)
                    result?.skeleton ?: "// No skeleton available — analyze the file first"
                }

        val factory = DiffContentFactory.getInstance()
        val originalContent = factory.create(content)
        val skeletonContent = factory.create(skeleton)

        val request =
            SimpleDiffRequest(
                "⚡ TokenSlayer: ${file.name}",
                originalContent,
                skeletonContent,
                "Original (${content.lines().size} lines)",
                "Skeleton (${skeleton.lines().size} lines)",
            )

        DiffManager.getInstance().showDiff(project, request)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null &&
            file.extension?.lowercase() in TokenSlayerService.SUPPORTED_EXTENSIONS
    }
}
