package com.tokenslayer.copilot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tokenslayer.extraction.SymbolExpander
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

        /** Keeps a "symbol not found" error from returning an unbounded list on a huge file. */
        const val MAX_SYMBOL_LIST_CHARS = 2_000

        const val EXPAND_TOOL_NAME = "tokenslayer_expand"
        const val EXPAND_TOOL_DESCRIPTION = """
            Returns the real source of a single symbol (function, method, class, field) from a file.
            Use this after tokenslayer_structural_summary, when you have the skeleton and need one
            specific implementation. Prefer it over reading the whole file: it returns only the
            symbol's own lines, so the skeleton's token saving is preserved rather than thrown away
            by re-reading the file. If the symbol name is ambiguous or unknown, the error lists the
            symbols that are available in that file.
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

        // Prefer the user's configured (stable) port so a saved Copilot registration keeps
        // working across restarts; fall back to an ephemeral port if it's already taken.
        val preferredPort = TokenSlayerSettings.getInstance().mcpServerPort
        val server =
            createServerOn(preferredPort)
                ?: createServerOn(0)
                ?: run {
                    // Use warn, not error: a failed MCP start is recoverable and must NOT
                    // surface the IDE's red "Internal Error" dialog. Logger.error() in the
                    // IntelliJ Platform is reported to the fatal-error handler and shown to the user.
                    log.warn("Failed to start MCP server on any port")
                    return
                }

        server.createContext("/mcp", ::handleMcpRequest)
        server.createContext("/health") { ex ->
            respond(ex, 200, """{"status":"ok","server":"$SERVER_NAME"}""")
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        serverPort = server.address.port
        httpServer = server
        log.info("TokenSlayer MCP server started on http://localhost:$serverPort/mcp")
    }

    private fun createServerOn(port: Int): HttpServer? =
        try {
            HttpServer.create(InetSocketAddress("localhost", port), 0)
        } catch (e: Exception) {
            if (port != 0) log.warn("MCP port $port unavailable; will try an ephemeral port", e)
            null
        }

    /**
     * Path to GitHub Copilot for JetBrains' MCP config file. Copilot reads MCP servers from
     * this global file (it does NOT read a per-repo file), so this is where a user registers
     * TokenSlayer. See getServerUrl()/getCopilotConfigSnippet() for the entry to add.
     */
    fun getCopilotConfigPath(): String = System.getProperty("user.home") + "/.config/github-copilot/intellij/mcp.json"

    /** The JSON entry a user pastes into [getCopilotConfigPath] to register this server. */
    fun getCopilotConfigSnippet(): String =
        """
        {
          "servers": {
            "tokenslayer": {
              "type": "http",
              "url": "${getServerUrl()}"
            }
          }
        }
        """.trimIndent()

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
                    // Read from the plugin descriptor rather than a literal, which had been left
                    // at 0.2.0 across several releases.
                    addProperty("version", pluginVersion())
                },
            )
        }

    private fun stringProperty(description: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
        }

    private fun jsonArrayOf(vararg values: String): com.google.gson.JsonArray =
        com.google.gson.JsonArray().apply { values.forEach { add(it) } }

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
                                        stringProperty(
                                            "Absolute path to the file to summarize. Omit to use the currently active file.",
                                        ),
                                    )
                                    add(
                                        "verbosity",
                                        stringProperty("Skeleton verbosity level. Defaults to 'standard'.").apply {
                                            // JSON Schema requires `enum` to be an array. This was a
                                            // comma-joined string, which validating clients ignore.
                                            add("enum", jsonArrayOf("minimal", "standard", "detailed"))
                                        },
                                    )
                                },
                            )
                            add("required", com.google.gson.JsonArray())
                        },
                    )
                },
            )

            tools.add(
                JsonObject().apply {
                    addProperty("name", EXPAND_TOOL_NAME)
                    addProperty("description", EXPAND_TOOL_DESCRIPTION.trimIndent())
                    add(
                        "inputSchema",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        "symbol",
                                        stringProperty(
                                            "Name of the symbol to expand, as it appears in the skeleton. " +
                                                "Qualify it (\"MyClass.doThing\") to disambiguate when a bare " +
                                                "name occurs more than once.",
                                        ),
                                    )
                                    add(
                                        "filePath",
                                        stringProperty(
                                            "Absolute path to the file containing the symbol. " +
                                                "Omit to use the currently active file.",
                                        ),
                                    )
                                },
                            )
                            add("required", jsonArrayOf("symbol"))
                        },
                    )
                },
            )

            add("tools", tools)
        }

    // ── Tool Execution ────────────────────────────────────────────────────────

    /**
     * Which open project owns [path]? Prefers a real content-root match, falling back to a
     * base-path prefix so files not yet in the VFS (or projects still loading their roots)
     * still resolve. For nested projects the deepest base path wins.
     */
    private fun findProjectContaining(
        projects: List<Project>,
        path: String,
    ): Project? {
        val vFile = LocalFileSystem.getInstance().findFileByPath(path)
        if (vFile != null) {
            val owner =
                ApplicationManager.getApplication().runReadAction(
                    Computable {
                        projects.firstOrNull { p ->
                            !p.isDisposed && ProjectFileIndex.getInstance(p).isInContent(vFile)
                        }
                    },
                )
            if (owner != null) return owner
        }

        val normalized = path.replace('\\', '/')
        return projects
            .filter { !it.isDisposed && it.basePath != null }
            .filter { normalized.startsWith(it.basePath!!.replace('\\', '/').trimEnd('/') + "/") }
            .maxByOrNull { it.basePath!!.length }
    }

    /** The project owning the focused editor, used to disambiguate a path-less call. */
    private fun projectWithActiveEditor(projects: List<Project>): Project? {
        var found: Project? = null
        ApplicationManager.getApplication().invokeAndWait {
            found =
                projects.firstOrNull { p ->
                    !p.isDisposed && FileEditorManager.getInstance(p).selectedFiles.isNotEmpty()
                }
        }
        return found
    }

    /** A resolved (project, filePath) pair, or the error to return instead. */
    private sealed interface Target {
        data class Resolved(val project: com.intellij.openapi.project.Project, val filePath: String) : Target

        data class Failed(val error: JsonObject) : Target
    }

    private fun executeTool(
        toolName: String,
        args: JsonObject,
    ): JsonObject =
        when (toolName) {
            TOOL_NAME -> executeStructuralSummary(args)
            EXPAND_TOOL_NAME -> executeExpand(args)
            else -> buildToolError("Unknown tool: $toolName")
        }

    /**
     * Both tools take an optional filePath and fall back to the active editor, and both must
     * resolve which open workspace the call belongs to. Shared so the two cannot drift apart.
     */
    private fun resolveTarget(args: JsonObject): Target {
        val openProjects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        if (openProjects.isEmpty()) return Target.Failed(buildToolError("No open project found"))

        val requestedPath = args.get("filePath")?.asString?.takeIf { it.isNotBlank() }

        // Resolve which workspace this call refers to. When a path is given, pick the project
        // that actually contains it — the server is a single application-level endpoint, so
        // taking openProjects.first() served skeletons (and cache state) from an arbitrary
        // workspace whenever the user had more than one window open.
        val project =
            when {
                requestedPath != null ->
                    findProjectContaining(openProjects, requestedPath)
                        ?: return Target.Failed(
                            buildToolError("File is not part of any open project: $requestedPath"),
                        )
                openProjects.size == 1 -> openProjects.single()
                else ->
                    // No path and several candidates: fall back to whichever project owns the
                    // focused editor rather than guessing.
                    projectWithActiveEditor(openProjects)
                        ?: return Target.Failed(
                            buildToolError(
                                "Multiple projects are open — pass an absolute filePath to disambiguate.",
                            ),
                        )
            }

        val filePath =
            requestedPath
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
                ?: return Target.Failed(buildToolError("No file specified and no active editor file"))

        return Target.Resolved(project, filePath)
    }

    private fun executeStructuralSummary(args: JsonObject): JsonObject {
        // Optional per-call verbosity. When absent, the configured default is used and the
        // shared cache is consulted; when present, a fresh skeleton is built at that verbosity.
        val verbosityOverride =
            args.get("verbosity")?.asString?.takeIf { it.isNotBlank() }?.let { Verbosity.from(it) }

        val (project, filePath) =
            when (val t = resolveTarget(args)) {
                is Target.Failed -> return t.error
                is Target.Resolved -> t.project to t.filePath
            }

        val tsService = TokenSlayerService.getInstance(project)

        // Fast path: at the default verbosity, serve an already-cached skeleton if present.
        if (verbosityOverride == null) {
            val cached = tsService.getCachedEntry(filePath)
            if (cached != null) {
                tsService.recordServe(filePath, TOOL_NAME, cached.originalTokens, cached.skeletonTokens)
                return buildToolSuccess(cached.skeleton, filePath, fromCache = true)
            }
        }

        // Find VirtualFile and analyze (honoring the requested verbosity).
        val vFile =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return buildToolError("File not found: $filePath")

        val result =
            tsService.analyzeFile(vFile, verbosityOverride)
                ?: return buildToolError("Could not analyze file: $filePath (unsupported type or read error)")

        if (result.secretsScan.hasSecrets) {
            return buildToolError("File excluded — contains sensitive data: ${result.secretsScan.reasons.joinToString(", ")}")
        }

        tsService.recordServe(filePath, TOOL_NAME, result.originalTokens, result.skeletonTokens)

        return buildToolSuccess(
            result.skeleton,
            filePath,
            fromCache = result.fromCache,
            originalTokens = result.originalTokens,
            skeletonTokens = result.skeletonTokens,
        )
    }

    /**
     * Return one symbol's real source. The half of the loop that was missing: without it an
     * assistant holding a skeleton had to re-read the whole file to see any implementation,
     * discarding the saving on the very file it had just economised on.
     */
    private fun executeExpand(args: JsonObject): JsonObject {
        val symbolQuery =
            args.get("symbol")?.asString?.takeIf { it.isNotBlank() }
                ?: return buildToolError("Missing required argument: symbol")

        val (project, filePath) =
            when (val t = resolveTarget(args)) {
                is Target.Failed -> return t.error
                is Target.Resolved -> t.project to t.filePath
            }

        val vFile =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return buildToolError("File not found: $filePath")

        val tsService = TokenSlayerService.getInstance(project)
        val outcome =
            tsService.expandSymbol(vFile, symbolQuery)
                ?: return buildToolError(
                    "Could not expand in $filePath — unsupported file type, unreadable, " +
                        "ignored by settings, or excluded for containing sensitive data.",
                )

        return when (outcome) {
            is SymbolExpander.Outcome.NotFound ->
                buildToolError(
                    buildString {
                        append("No symbol named '$symbolQuery' in $filePath.")
                        if (outcome.available.isEmpty()) {
                            append(" No symbols were extracted from this file.")
                        } else {
                            append(" Available symbols: ")
                            append(outcome.available.joinToString(", ").take(MAX_SYMBOL_LIST_CHARS))
                        }
                    },
                )

            is SymbolExpander.Outcome.Ambiguous ->
                buildToolError(
                    "'$symbolQuery' is ambiguous in $filePath. Re-request with one of: " +
                        outcome.candidates.joinToString(", ").take(MAX_SYMBOL_LIST_CHARS),
                )

            is SymbolExpander.Outcome.Found -> {
                val e = outcome.expanded
                tsService.recordServe(filePath, EXPAND_TOOL_NAME, e.fileTokens, e.sourceTokens)
                buildToolSuccess(
                    "// ${e.kind} ${e.qualifiedName} — ${basename(filePath)}:${e.startLine}-${e.endLine}\n" +
                        e.source,
                    filePath,
                    fromCache = false,
                    originalTokens = e.fileTokens,
                    skeletonTokens = e.sourceTokens,
                )
            }
        }
    }

    private fun basename(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Plugin version, read from a resource generated by the build.
     *
     * Not looked up through the platform: both PluginManagerCore.getPlugin(PluginId) and
     * PluginManager.getPluginByClass are @ApiStatus.Internal and the Plugin Verifier flags them.
     * A literal is worse still — this field silently read 0.2.0 for several releases.
     */
    private fun pluginVersion(): String =
        runCatching {
            javaClass.getResourceAsStream("/tokenslayer-version.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull() ?: "unknown"

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
