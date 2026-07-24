package com.tokenslayer.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.tokenslayer.services.ProjectAnalyzerService
import com.tokenslayer.services.TokenSlayerService

/** Analyze the currently active file. */
class AnalyzeCurrentFileAction : AnAction("Analyze Current File"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        ProjectAnalyzerService.getInstance(project).analyzeFile(file)
    }

    // Required since 2022.3+: actions overriding update() must declare their update thread.
    // BGT is correct here — update() only reads the (thread-safe) VIRTUAL_FILE data key.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null &&
            file.extension?.lowercase() in TokenSlayerService.SUPPORTED_EXTENSIONS
    }
}
