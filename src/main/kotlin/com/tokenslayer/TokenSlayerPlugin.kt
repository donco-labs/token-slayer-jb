package com.tokenslayer

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.*
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.services.ProjectAnalyzerService
import com.tokenslayer.services.TokenSlayerService

/**
 * Plugin startup activity. Runs when a project opens.
 * - Triggers background workspace analysis
 * - Registers VirtualFile listener for auto-invalidation on file changes
 */
class TokenSlayerPlugin : ProjectActivity {
    private val log = logger<TokenSlayerPlugin>()

    override suspend fun execute(project: Project) {
        log.info("TokenSlayer: Starting up for project '${project.name}'")

        // Start the embedded MCP server and write copilot MCP config
        val mcpServer = com.tokenslayer.copilot.TokenSlayerMcpServer.getInstance()
        writeMcpConfig(project, mcpServer.serverPort)

        // Register VFS listener to invalidate cache on file changes
        VirtualFileManager.getInstance().addAsyncFileListener(
            TokenSlayerAsyncFileListener(project),
            project,
        )

        // Trigger workspace analysis in background
        ProjectAnalyzerService.getInstance(project).analyzeAll { count ->
            log.info("TokenSlayer: Initial scan complete — $count files analyzed")
        }
    }

    /**
     * Write .github/copilot-mcp.json so GitHub Copilot auto-discovers our MCP server.
     * This follows the official MCP server discovery mechanism for GitHub Copilot.
     */
    private fun writeMcpConfig(
        project: Project,
        port: Int,
    ) {
        val basePath = project.basePath ?: return
        try {
            val mcpDir = java.io.File(basePath, ".github")
            mcpDir.mkdirs()
            val mcpFile = java.io.File(mcpDir, "copilot-mcp.json")
            mcpFile.writeText(
                """
                {
                  "servers": {
                    "TokenSlayer": {
                      "type": "http",
                      "url": "http://localhost:$port/mcp",
                      "description": "TokenSlayer: AST skeleton provider. Reduces AI token usage by 40-95%."
                    }
                  }
                }
                """.trimIndent(),
            )
            log.info("TokenSlayer: Wrote MCP config to ${mcpFile.path}")
        } catch (e: Exception) {
            log.warn("TokenSlayer: Could not write MCP config", e)
        }
    }
}

/**
 * Listens for file changes and invalidates stale cache entries.
 */
private class TokenSlayerAsyncFileListener(private val project: Project) : com.intellij.openapi.vfs.AsyncFileListener {
    private val cache get() = CacheManager.getInstance()
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
                            analyzer.analyzeFile(file)
                        }
                    }
                }
            }
        }
    }
}
