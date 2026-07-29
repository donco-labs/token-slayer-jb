package com.tokenslayer.types

// ─── Verbosity ────────────────────────────────────────────────────────────────

enum class Verbosity(val label: String) {
    MINIMAL("minimal"),
    STANDARD("standard"),
    DETAILED("detailed"),
    ;

    companion object {
        fun from(value: String): Verbosity = entries.firstOrNull { it.label == value } ?: STANDARD
    }
}

// ─── Symbol kinds (mirrors VS Code's SymbolKind) ──────────────────────────────

enum class SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    ENUM_MEMBER,
    STRUCT,
    MODULE,
    NAMESPACE,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    FIELD,
    VARIABLE,
    CONSTANT,
    OBJECT,
    TYPE_ALIAS,
    TRAIT,
    IMPL,
    UNKNOWN,
}

// ─── Core data classes ────────────────────────────────────────────────────────

/**
 * A structural symbol extracted from a source file — mirrors VS Code's StructuralSymbol.
 */
data class StructuralSymbol(
    val name: String,
    val kind: SymbolKind,
    val kindLabel: String,
    val signatureLine: String,
    val lineRange: IntRange,
    val children: List<StructuralSymbol> = emptyList(),
)

/**
 * A cached skeleton entry for a single file.
 */
data class CacheEntry(
    val skeleton: String,
    val originalTokens: Int,
    val skeletonTokens: Int,
    val contentHash: String,
    val language: String,
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val tokensSaved: Int get() = originalTokens - skeletonTokens
    val reductionPct: Int get() =
        if (originalTokens > 0) ((tokensSaved.toDouble() / originalTokens) * 100).toInt() else 0
}

/**
 * Result of a secrets scan.
 */
data class SecretsScanResult(
    val hasSecrets: Boolean,
    val reasons: List<String>,
    val severity: Severity,
) {
    enum class Severity { LOW, MEDIUM, HIGH }
}

/**
 * Analysis result for a single file.
 */
data class FileAnalysisResult(
    val filePath: String,
    val language: String,
    val skeleton: String,
    val originalTokens: Int,
    val skeletonTokens: Int,
    val secretsScan: SecretsScanResult,
    val fromCache: Boolean = false,
)

/**
 * A single symbol's real source, returned by the `tokenslayer_expand` tool.
 *
 * The skeleton tells an assistant what exists; this gives back one implementation without
 * re-reading the whole file. Without it, the moment the assistant needs a body it must read the
 * entire file and the skeleton's saving is lost on exactly the file already paid for.
 */
data class ExpandedSymbol(
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val filePath: String,
    /** 1-based, inclusive — line numbers as a human or an LLM would cite them. */
    val startLine: Int,
    val endLine: Int,
    val source: String,
    val sourceTokens: Int,
    val fileTokens: Int,
) {
    val tokensSaved: Int get() = (fileTokens - sourceTokens).coerceAtLeast(0)
}

/**
 * One record of content actually served to an assistant over MCP.
 *
 * Analysis stats measure what *could* be saved across every file scanned; these measure what was
 * genuinely delivered. Without them there is no way to tell whether an assistant has ever called
 * the tools at all.
 */
data class ServeRecord(
    val filePath: String,
    val tool: String,
    val originalTokens: Int,
    val servedTokens: Int,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val tokensSaved: Int get() = (originalTokens - servedTokens).coerceAtLeast(0)
}

// ─── Stats ────────────────────────────────────────────────────────────────────

/**
 * Aggregated workspace-level token savings stats.
 */
data class WorkspaceStats(
    val totalTokensSaved: Int = 0,
    val totalOriginalTokens: Int = 0,
    val filesAnalyzed: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val excludedFiles: Int = 0,
    val languageBreakdown: Map<String, LanguageStat> = emptyMap(),
    val topSavers: List<CacheEntry> = emptyList(),
    val recentActivity: List<CacheEntry> = emptyList(),
    val excludedFilesList: List<ExcludedFile> = emptyList(),
    // ── Realized (actually delivered over MCP), as opposed to the potential figures above ──
    val realizedTokensSaved: Int = 0,
    val realizedOriginalTokens: Int = 0,
    val mcpServeCount: Int = 0,
    val recentServes: List<ServeRecord> = emptyList(),
) {
    /** Reduction across content genuinely served to an assistant. */
    val realizedReductionPct: Int get() =
        if (realizedOriginalTokens > 0) {
            ((realizedTokensSaved.toDouble() / realizedOriginalTokens) * 100).toInt()
        } else {
            0
        }

    val reductionPct: Int get() =
        if (totalOriginalTokens > 0) {
            ((totalTokensSaved.toDouble() / totalOriginalTokens) * 100).toInt()
        } else {
            0
        }
    val cacheHitRate: Int get() {
        val total = cacheHits + cacheMisses
        return if (total > 0) ((cacheHits.toDouble() / total) * 100).toInt() else 0
    }
}

data class LanguageStat(
    val language: String,
    val files: Int = 0,
    val tokensSaved: Int = 0,
    val reductionPct: Int = 0,
)

data class ExcludedFile(
    val filePath: String,
    val reasons: List<String>,
    val severity: SecretsScanResult.Severity,
)
