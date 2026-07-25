package com.tokenslayer

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.*
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.services.ProjectAnalyzerService
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.settings.TokenSlayerSettings

/**
 * Plugin startup activity. Runs when a project opens.
 * - Triggers background workspace analysis
 * - Registers VirtualFile listener for auto-invalidation on file changes
 */
class TokenSlayerPlugin : ProjectActivity {
    private val log = logger<TokenSlayerPlugin>()

    override suspend fun execute(project: Project) {
        log.info("TokenSlayer: Starting up for project '${project.name}'")

        // Start the embedded MCP server. We deliberately do NOT write any file into the
        // user's project (the old code wrote .github/copilot-mcp.json). That is the VS Code
        // discovery convention — GitHub Copilot for JetBrains does not read it — and writing
        // into a version-controlled directory is intrusive and can be committed by accident.
        // JetBrains guidance is to keep plugin state in the IDE, not the project. The server
        // URL is exposed in Settings → Tools → TokenSlayer for manual registration instead.
        try {
            val mcpServer = com.tokenslayer.copilot.TokenSlayerMcpServer.getInstance()
            log.info("TokenSlayer: MCP server available at ${mcpServer.getServerUrl()}")
        } catch (e: Exception) {
            log.warn("TokenSlayer: MCP server unavailable", e)
        }

        // Register VFS listener to invalidate cache on file changes
        VirtualFileManager.getInstance().addAsyncFileListener(
            TokenSlayerAsyncFileListener(project),
            project,
        )

        // Trigger workspace analysis in background — but only if the user wants it. The
        // "Auto-analyze" setting was previously read nowhere, so this scan always ran and
        // could not be turned off. No completion balloon here: with several workspaces
        // restored at startup that would be one balloon per window.
        if (TokenSlayerSettings.getInstance().autoAnalyzeOnOpen) {
            ProjectAnalyzerService.getInstance(project).analyzeAll { count ->
                log.info("TokenSlayer: Initial scan complete — $count files analyzed")
            }
        } else {
            log.info("TokenSlayer: Auto-analyze on open is disabled, skipping initial scan")
        }
    }
}

/**
 * Listens for file changes and invalidates stale cache entries.
 */
private class TokenSlayerAsyncFileListener(private val project: Project) : com.intellij.openapi.vfs.AsyncFileListener {
    private val cache get() = CacheManager.getInstance(project)
    private val analyzer get() = ProjectAnalyzerService.getInstance(project)

    override fun prepareChange(
        events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>,
    ): com.intellij.openapi.vfs.AsyncFileListener.ChangeApplier {
        return object : com.intellij.openapi.vfs.AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                for (event in events) {
                    val file = event.file ?: continue
                    if (
                        event is com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent ||
                        event is com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
                    ) {
                        cache.invalidate(event.path)
                    }
                    if (
                        event is com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent ||
                        event is com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent ||
                        event is com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
                    ) {
                        if (file.extension?.lowercase() in TokenSlayerService.SUPPORTED_EXTENSIONS) {
                            if (event is com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent) {
                                cache.invalidate(event.oldPath)
                            }
                            cache.invalidate(file.path)
                            // VFS events are application-wide, but this listener is registered
                            // once per open project. Without this guard every project would
                            // analyze every other project's files into its own cache — exactly
                            // the cross-workspace mixing the per-project services fix.
                            if (TokenSlayerSettings.getInstance().autoAnalyzeOnOpen &&
                                belongsToProject(file)
                            ) {
                                analyzer.analyzeFile(file)
                            }
                        }
                    }
                }
            }
        }
    }

    /** True when [file] is inside this project's content roots. */
    private fun belongsToProject(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (project.isDisposed) return false
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
            com.intellij.openapi.util.Computable {
                !project.isDisposed &&
                    com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(file)
            },
        )
    }
}
