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
        @Suppress("DEPRECATION")
        VirtualFileManager.getInstance().addVirtualFileListener(
            TokenSlayerVfsListener(project),
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
    private fun writeMcpConfig(project: Project, port: Int) {
        val basePath = project.basePath ?: return
        try {
            val mcpDir = java.io.File(basePath, ".github")
            mcpDir.mkdirs()
            val mcpFile = java.io.File(mcpDir, "copilot-mcp.json")
            mcpFile.writeText("""
                {
                  "servers": {
                    "TokenSlayer": {
                      "type": "http",
                      "url": "http://localhost:$port/mcp",
                      "description": "TokenSlayer: AST skeleton provider. Reduces AI token usage by 40-95%."
                    }
                  }
                }
            """.trimIndent())
            log.info("TokenSlayer: Wrote MCP config to ${mcpFile.path}")
        } catch (e: Exception) {
            log.warn("TokenSlayer: Could not write MCP config", e)
        }
    }
}

/**
 * Listens for file changes and invalidates stale cache entries.
 */
private class TokenSlayerVfsListener(private val project: Project) : VirtualFileListener {

    private val cache get() = CacheManager.getInstance()
    private val analyzer get() = ProjectAnalyzerService.getInstance(project)

    override fun contentsChanged(event: VirtualFileEvent) {
        val file = event.file
        if (file.extension?.lowercase() !in TokenSlayerService.SUPPORTED_EXTENSIONS) return
        // Invalidate old entry and re-analyze
        cache.invalidate(file.path)
        analyzer.analyzeFile(file)
    }

    override fun fileDeleted(event: VirtualFileEvent) {
        cache.invalidate(event.file.path)
    }

    override fun fileMoved(event: VirtualFileMoveEvent) {
        val oldPath = "${event.oldParent.path}/${event.file.name}"
        cache.invalidate(oldPath)
        cache.invalidate(event.file.path)
        analyzer.analyzeFile(event.file)
    }
}
