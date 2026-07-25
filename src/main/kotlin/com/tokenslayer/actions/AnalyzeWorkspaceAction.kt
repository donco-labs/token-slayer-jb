package com.tokenslayer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.tokenslayer.services.ProjectAnalyzerService

/** Analyze all files in the workspace. */
class AnalyzeWorkspaceAction : AnAction("Analyze Workspace"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProjectAnalyzerService.getInstance(project).analyzeAll(notifyOnComplete = true)
    }
}
