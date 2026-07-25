package com.tokenslayer.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-scoped service that coordinates workspace-wide analysis.
 * Iterates ProjectFileIndex to find all supported files, skipping ignored paths.
 */
@Service(Service.Level.PROJECT)
class ProjectAnalyzerService(private val project: Project) : DumbAware {
    private val log = logger<ProjectAnalyzerService>()
    private val tsService get() = TokenSlayerService.getInstance(project)

    companion object {
        fun getInstance(project: Project): ProjectAnalyzerService = project.getService(ProjectAnalyzerService::class.java)
    }

    // ── Observable progress ──────────────────────────────────────────────────
    // A Task.Backgroundable reports into the status-bar widget of *its own project frame* and
    // never shows a dialog. That is the right call for a whole-workspace scan (blocking the IDE
    // would be worse), but it meant the startup scan was easy to miss entirely — and with
    // several workspaces open the indicator sat in whichever frame owned the project, i.e.
    // behind another window. Exposing the state here lets the dashboard show it where the user
    // is actually looking.

    @Volatile
    var isAnalyzing: Boolean = false
        private set

    @Volatile
    var progressProcessed: Int = 0
        private set

    @Volatile
    var progressTotal: Int = 0
        private set

    private val running = AtomicBoolean(false)

    /**
     * Analyze all supported files in the project asynchronously with progress.
     *
     * @param notifyOnComplete show a completion balloon. Set for explicitly requested runs
     *   (the action, the dashboard button) where the user is waiting on an answer; left off for
     *   the automatic startup scan, which would otherwise fire one balloon per open workspace.
     */
    fun analyzeAll(
        notifyOnComplete: Boolean = false,
        onComplete: ((Int) -> Unit)? = null,
    ) {
        // Startup and a user-clicked "Analyze Workspace" can otherwise overlap and double-scan.
        if (!running.compareAndSet(false, true)) {
            log.info("TokenSlayer: Analysis already in progress for ${project.name}, ignoring request")
            if (notifyOnComplete) {
                notify("Analysis is already running for this project.", NotificationType.INFORMATION)
            }
            return
        }
        isAnalyzing = true
        progressProcessed = 0
        progressTotal = 0

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
                    progressTotal = total
                    log.info("TokenSlayer: Found $total supported files in ${project.name}")

                    var processed = 0
                    for (file in files) {
                        if (indicator.isCanceled) break
                        indicator.fraction = processed.toDouble() / total
                        indicator.text2 = file.name

                        try {
                            tsService.analyzeFile(file)
                        } catch (e: Exception) {
                            log.warn("Error analyzing ${file.path}", e)
                        }
                        processed++
                        progressProcessed = processed
                    }

                    log.info("TokenSlayer: Workspace analysis complete ($processed/$total files)")
                    if (notifyOnComplete && !indicator.isCanceled) {
                        notify(
                            "Analyzed $processed of $total files in ${project.name}.",
                            NotificationType.INFORMATION,
                        )
                    }
                    ApplicationManager.getApplication().invokeLater {
                        onComplete?.invoke(processed)
                    }
                }

                override fun onFinished() {
                    // Runs on success, cancellation and failure alike, so the flag can't stick.
                    isAnalyzing = false
                    running.set(false)
                }
            },
        )
    }

    /**
     * Analyze a single file (called from file open / save events).
     */
    fun analyzeFile(file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            tsService.analyzeFile(file)
        }
    }

    private fun collectSupportedFiles(): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        try {
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
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            // On 2024.1+ ProcessCanceledException extends CancellationException, but a bare
            // coroutine cancellation can also surface here. Neither may be swallowed.
            throw e
        } catch (e: Exception) {
            // IntelliJ's on-disk VFS cache can become corrupted independently of this plugin
            // (e.g. com.intellij.util.io.CorruptedException wrapped in UncheckedIOException).
            // iterateContent() throws straight through Task.Backgroundable.run() in that case,
            // which IntelliJ reports as a plugin crash. Degrade gracefully instead: keep
            // whatever files were already collected and let the user know how to recover.
            log.warn("TokenSlayer: Failed to enumerate project files, VFS may be corrupted", e)
            notifyIndexFailure(e)
        }
        return result
    }

    private fun notifyIndexFailure(e: Throwable) {
        val message = if (hasIOCause(e)) {
            "Project index appears corrupted — try File > Invalidate Caches and Restart."
        } else {
            "Could not scan the project (${e.javaClass.simpleName}). Workspace analysis was skipped."
        }
        notify(message, NotificationType.WARNING)
    }

    /**
     * Raise a project-scoped balloon. Notifications are safe from a background thread, and
     * raising it synchronously avoids the window where the project is disposed between
     * scheduling and delivery — a scan can outlive the project, especially a slow one.
     */
    private fun notify(
        message: String,
        type: NotificationType,
    ) {
        if (project.isDisposed) {
            log.info("TokenSlayer: Project disposed, skipping notification: $message")
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokenSlayer")
            .createNotification("TokenSlayer: $message", type)
            .notify(project)
    }

    private fun hasIOCause(e: Throwable): Boolean {
        var cause: Throwable? = e
        // Bounded: a malformed cause chain can be cyclic (A -> B -> A).
        var depth = 0
        while (cause != null && depth < 32) {
            if (cause is IOException) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
