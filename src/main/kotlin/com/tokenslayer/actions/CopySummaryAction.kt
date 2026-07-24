package com.tokenslayer.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.tokenslayer.services.TokenSlayerService
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Copies the structural skeleton of the current file to clipboard.
 * Use this to paste directly into GitHub Copilot Chat for manual context injection.
 */
class CopySummaryAction : AnAction("Copy Skeleton Summary"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val tsService = TokenSlayerService.getInstance()
        val skeleton =
            tsService.getCachedSkeleton(file.path)
                ?: tsService.analyzeFile(file, project)?.skeleton
                ?: return

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(skeleton), null)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenSlayer")
            .createNotification("⚡ Skeleton copied to clipboard", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null &&
            file.extension?.lowercase() in TokenSlayerService.SUPPORTED_EXTENSIONS
    }
}
