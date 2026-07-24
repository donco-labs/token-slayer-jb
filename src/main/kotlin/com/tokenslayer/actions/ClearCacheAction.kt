package com.tokenslayer.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.tokenslayer.cache.CacheManager

/** Clear all cached skeletons. */
class ClearCacheAction : AnAction("Clear Cache"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cache = CacheManager.getInstance(project)
        val count = cache.size
        cache.clear()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenSlayer")
            .createNotification("⚡ TokenSlayer cache cleared ($count entries)", NotificationType.INFORMATION)
            .notify(project)
    }
}
