package com.tokenslayer.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.types.WorkspaceStats
import com.tokenslayer.utils.TokenEstimator
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exports a Markdown savings report to the project root.
 * Equivalent of VS Code's "TokenSlayer: Export Savings Report".
 */
class ExportReportAction : AnAction("Export Savings Report"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        doExport(project)
    }

    companion object {
        fun doExport(project: Project) {
            val tsService = TokenSlayerService.getInstance(project)
            val stats = tsService.computeStats()
            val report = buildReport(stats, project.name)

            val projectBasePath = project.basePath ?: return
            val reportFile = File(projectBasePath, "tokenslayer-report.md")

            WriteAction.runAndWait<Exception> {
                reportFile.writeText(report, Charsets.UTF_8)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(reportFile)
            }

            NotificationGroupManager.getInstance()
                .getNotificationGroup("TokenSlayer")
                .createNotification(
                    "⚡ TokenSlayer Report exported",
                    "Saved to ${reportFile.name}",
                    NotificationType.INFORMATION,
                ).notify(project)
        }

        private fun buildReport(
            stats: WorkspaceStats,
            projectName: String,
        ): String {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            return buildString {
                appendLine("# ⚡ TokenSlayer Report")
                appendLine()
                appendLine("**Project:** $projectName  ")
                appendLine("**Generated:** $timestamp")
                appendLine()
                appendLine("## Summary")
                appendLine()
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| Tokens Saved | ${TokenEstimator.format(stats.totalTokensSaved)} |")
                appendLine("| Reduction | ${stats.reductionPct}% |")
                appendLine("| Files Analyzed | ${stats.filesAnalyzed} |")
                appendLine("| Cache Hit Rate | ${stats.cacheHitRate}% |")
                appendLine("| Excluded Files | ${stats.excludedFiles} |")
                appendLine()
                appendLine("## Language Breakdown")
                appendLine()
                appendLine("| Language | Files | Tokens Saved | Reduction |")
                appendLine("|----------|-------|-------------|-----------|")
                stats.languageBreakdown.values.sortedByDescending { it.tokensSaved }.forEach { ls ->
                    appendLine("| ${ls.language} | ${ls.files} | ${TokenEstimator.format(ls.tokensSaved)} | ${ls.reductionPct}% |")
                }
                appendLine()
                appendLine("## Top 5 Files by Token Savings")
                appendLine()
                appendLine("| # | File | Saved | Reduction |")
                appendLine("|---|------|-------|-----------|")
                stats.topSavers.forEachIndexed { idx, entry ->
                    val name = entry.filePath.split("/", "\\").last()
                    appendLine("| ${idx + 1} | $name | ${TokenEstimator.format(entry.tokensSaved)} | ${entry.reductionPct}% |")
                }
                if (stats.excludedFilesList.isNotEmpty()) {
                    appendLine()
                    appendLine("## 🛡️ Excluded Files (Secrets Detected)")
                    appendLine()
                    appendLine("| File | Severity | Reason |")
                    appendLine("|------|----------|--------|")
                    stats.excludedFilesList.forEach { ef ->
                        val name = ef.filePath.split("/", "\\").last()
                        appendLine("| $name | ${ef.severity} | ${ef.reasons.firstOrNull() ?: ""} |")
                    }
                }
            }
        }
    }
}
