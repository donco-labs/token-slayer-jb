package com.tokenslayer.copilot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tokenslayer.services.TokenSlayerService
import com.tokenslayer.settings.TokenSlayerSettings
import com.tokenslayer.types.Verbosity
import com.tokenslayer.utils.TokenEstimator
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Embedded MCP (Model Context Protocol) server for GitHub Copilot integration.
 *
 * Registered via plugin.xml:
 *   <extensions defaultExtensionNs="com.github.copilot">
 *       <mcpServer implementation="com.tokenslayer.copilot.TokenSlayerMcpServer"/>
 *   </extensions>
 *
 * The server starts on a random localhost port and exposes the
 * `tokenslayer_structural_summary` tool — the JetBrains equivalent of
 * VS Code's #tokenslayer-structural-summary Language Model Tool.
 *
 * Note: The exact McpServer interface signature depends on the GitHub Copilot
 * JetBrains plugin version. This class implements the MCP HTTP server and
 * exposes getServerUrl() for the Copilot plugin to connect to.
 */
class TokenSlayerMcpServer {
    private val log = logger<TokenSlayerMcpServer>()
    private val gson = Gson()
    private var httpServer: HttpServer? = null
    var serverPort: Int = 0
        private set

    companion object {
        const val SERVER_NAME = "TokenSlayer"
        const val TOOL_NAME = "tokenslayer_structural_summary"
        const val TOOL_DESCRIPTION = """
            Returns a compact structural skeleton of a source file or the current project.
            Slashes token usage by 40-95% by replacing raw file content with an AST-driven skeleton.
            Use this instead of reading raw file content when you need to understand code structure,
            find classes/functions, or understand architectural relationships.
        """

        @Volatile
        private var instance: TokenSlayerMcpServer? = null

        fun getInstance(): TokenSlayerMcpServer {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val server = TokenSlayerMcpServer()
                    server.start()
                    instance = server
                    server
                }
            }
        }
    }

    /**
     * Start the embedded MCP HTTP server on a random available port.
     * Called by the Copilot plugin or by the plugin startup.
     */
    fun start() {
        if (httpServer != null) return
        try {
            val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            server.createContext("/mcp", ::handleMcpRequest)
            server.createContext("/health") { ex ->
                respond(ex, 200, """{"status":"ok","server":"$SERVER_NAME"}""")
            }
            server.executor = Executors.newCachedThreadPool()
            server.start()
            serverPort = server.address.port
            httpServer = server
            log.info("TokenSlayer MCP server started on http://localhost:$serverPort/mcp")
        } catch (e: Exception) {
            log.error("Failed to start MCP server", e)
        }
    }

    fun stop() {
        httpServer?.stop(1)
        httpServer = null
        log.info("TokenSlayer MCP server stopped")
    }

    /** URL for Copilot to connect to. */
    fun getServerUrl(): String = "http://localhost:$serverPort/mcp"

    /** Display name shown in Copilot's MCP server list. */
    fun getServerName(): String = SERVER_NAME

    // ── MCP Request Handler ───────────────────────────────────────────────────

    private fun handleMcpRequest(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"Method Not Allowed"}""")
            return
        }

        val body = exchange.requestBody.bufferedReader().readText()
        val response =
            try {
                val request = gson.fromJson(body, JsonObject::class.java)
                processJsonRpc(request)
            } catch (e: Exception) {
                log.warn("MCP request error", e)
                buildError(-32700, "Parse error: ${e.message}")
            }

        respond(exchange, 200, gson.toJson(response))
    }

    private fun processJsonRpc(request: JsonObject): JsonObject {
        val id = request.get("id")
        val method = request.get("method")?.asString ?: return buildError(-32600, "Missing method")

        return when (method) {
            "initialize" -> buildResult(id, buildInitializeResult())
            "tools/list" -> buildResult(id, buildToolsList())
            "tools/call" -> {
                val params = request.getAsJsonObject("params") ?: return buildError(-32602, "Missing params")
                val toolName = params.get("name")?.asString ?: return buildError(-32602, "Missing tool name")
                val args = params.getAsJsonObject("arguments") ?: JsonObject()
                val result = executeTool(toolName, args)
                buildResult(id, result)
            }
            else -> buildError(-32601, "Method not found: $method")
        }
    }

    private fun buildInitializeResult(): JsonObject =
        JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add(
                "capabilities",
                JsonObject().apply {
                    add("tools", JsonObject())
                },
            )
            add(
                "serverInfo",
                JsonObject().apply {
                    addProperty("name", SERVER_NAME)
                    addProperty("version", "0.2.0")
                },
            )
        }

    private fun buildToolsList(): JsonObject =
        JsonObject().apply {
            val tools = com.google.gson.JsonArray()
            tools.add(
                JsonObject().apply {
                    addProperty("name", TOOL_NAME)
                    addProperty("description", TOOL_DESCRIPTION.trimIndent())
                    add(
                        "inputSchema",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        "filePath",
                                        JsonObject().apply {
                                            addProperty("type", "string")
                                            addProperty(
                                                "description",
                                                "Absolute path to the file to summarize. Omit to use the currently active file.",
                                            )
                                        },
                                    )
                                    add(
                                        "verbosity",
                                        JsonObject().apply {
                                            addProperty("type", "string")
                                            addProperty("enum", "minimal,standard,detailed")
                                            addProperty("description", "Skeleton verbosity level. Defaults to 'standard'.")
                                        },
                                    )
                                },
                            )
                            add("required", com.google.gson.JsonArray())
                        },
                    )
                },
            )
            add("tools", tools)
        }

    // ── Tool Execution ────────────────────────────────────────────────────────

    private fun executeTool(
        toolName: String,
        args: JsonObject,
    ): JsonObject {
        if (toolName != TOOL_NAME) {
            return buildToolError("Unknown tool: $toolName")
        }

        val verbosity =
            args.get("verbosity")?.asString?.let { Verbosity.from(it) }
                ?: TokenSlayerSettings.getInstance().verbosity

        val project =
            ProjectManager.getInstance().openProjects.firstOrNull()
                ?: return buildToolError("No open project found")

        val filePath =
            args.get("filePath")?.asString?.takeIf { it.isNotBlank() }
                ?: run {
                    // Use active editor file
                    var active: String? = null
                    ApplicationManager.getApplication().invokeAndWait {
                        active =
                            FileEditorManager.getInstance(project)
                                .selectedFiles.firstOrNull()?.path
                    }
                    active
                }
                ?: return buildToolError("No file specified and no active editor file")

        // Try to get from cache first
        val cachedSkeleton = TokenSlayerService.getInstance().getCachedSkeleton(filePath)
        if (cachedSkeleton != null) {
            return buildToolSuccess(cachedSkeleton, filePath, fromCache = true)
        }

        // Find VirtualFile and analyze
        val vFile =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return buildToolError("File not found: $filePath")

        val result =
            TokenSlayerService.getInstance().analyzeFile(vFile, project)
                ?: return buildToolError("Could not analyze file: $filePath (unsupported type or read error)")

        if (result.secretsScan.hasSecrets) {
            return buildToolError("File excluded — contains sensitive data: ${result.secretsScan.reasons.joinToString(", ")}")
        }

        return buildToolSuccess(
            result.skeleton,
            filePath,
            fromCache = false,
            originalTokens = result.originalTokens,
            skeletonTokens = result.skeletonTokens,
        )
    }

    // ── JSON-RPC Helpers ──────────────────────────────────────────────────────

    private fun buildResult(
        id: com.google.gson.JsonElement?,
        result: JsonObject,
    ): JsonObject =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) add("id", id) else addProperty("id", 1)
            add("result", result)
        }

    private fun buildError(
        code: Int,
        message: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            add(
                "error",
                JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", message)
                },
            )
        }

    private fun buildToolSuccess(
        skeleton: String,
        filePath: String,
        fromCache: Boolean,
        originalTokens: Int = 0,
        skeletonTokens: Int = 0,
    ): JsonObject =
        JsonObject().apply {
            val content = com.google.gson.JsonArray()
            content.add(
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty(
                        "text",
                        buildString {
                            appendLine(skeleton)
                            appendLine()
                            appendLine("---")
                            appendLine("📁 File: $filePath")
                            if (originalTokens > 0) {
                                val saved = originalTokens - skeletonTokens
                                val pct = ((saved.toDouble() / originalTokens) * 100).toInt()
                                appendLine(
                                    "⚡ Token reduction: ${TokenEstimator.format(
                                        originalTokens,
                                    )} → ${TokenEstimator.format(skeletonTokens)} ($pct% saved)",
                                )
                            }
                            if (fromCache) appendLine("✅ Served from cache")
                        },
                    )
                },
            )
            add("content", content)
        }

    private fun buildToolError(message: String): JsonObject =
        JsonObject().apply {
            val content = com.google.gson.JsonArray()
            content.add(
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "Error: $message")
                },
            )
            add("content", content)
            addProperty("isError", true)
        }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
