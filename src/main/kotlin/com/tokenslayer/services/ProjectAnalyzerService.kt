package com.tokenslayer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * Project-scoped service that coordinates workspace-wide analysis.
 * Iterates ProjectFileIndex to find all supported files, skipping ignored paths.
 */
@Service(Service.Level.PROJECT)
class ProjectAnalyzerService(private val project: Project) : DumbAware {
    private val log = logger<ProjectAnalyzerService>()
    private val tsService get() = TokenSlayerService.getInstance()

    companion object {
        fun getInstance(project: Project): ProjectAnalyzerService = project.getService(ProjectAnalyzerService::class.java)
    }

    /**
     * Analyze all supported files in the project asynchronously with progress.
     */
    fun analyzeAll(onComplete: ((Int) -> Unit)? = null) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                "TokenSlayer: Analyzing workspace…",
                true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val files = collectSupportedFiles()
                    val total = files.size
                    log.info("TokenSlayer: Found $total supported files in ${project.name}")

                    var processed = 0
                    for (file in files) {
                        if (indicator.isCanceled) break
                        indicator.fraction = processed.toDouble() / total
                        indicator.text2 = file.name

                        try {
                            tsService.analyzeFile(file, project)
                        } catch (e: Exception) {
                            log.warn("Error analyzing ${file.path}", e)
                        }
                        processed++
                    }

                    log.info("TokenSlayer: Workspace analysis complete ($processed/$total files)")
                    ApplicationManager.getApplication().invokeLater {
                        onComplete?.invoke(processed)
                    }
                }
            },
        )
    }

    /**
     * Analyze a single file (called from file open / save events).
     */
    fun analyzeFile(file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            tsService.analyzeFile(file, project)
        }
    }

    private fun collectSupportedFiles(): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        ApplicationManager.getApplication().runReadAction {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory &&
                    file.extension?.lowercase() in TokenSlayerService.SUPPORTED_EXTENSIONS
                ) {
                    result.add(file)
                }
                true // continue
            }
        }
        return result
    }
}
