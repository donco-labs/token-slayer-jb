package com.tokenslayer.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.compaction.CompactorFactory
import com.tokenslayer.extraction.PsiSymbolExtractor
import com.tokenslayer.extraction.SkeletonBuilder
import com.tokenslayer.extraction.SymbolExpander
import com.tokenslayer.settings.TokenSlayerSettings
import com.tokenslayer.types.*
import com.tokenslayer.utils.SecretsDetector
import com.tokenslayer.utils.TokenEstimator

/**
 * Project-level service.
 * Core orchestrator: extracts symbols, builds skeletons, manages cache and stats.
 *
 * PROJECT-scoped on purpose — see the note on [CacheManager]. The cache, the excluded-file
 * list, the recent-activity list and the cache hit/miss counters are all per-workspace state,
 * so a service instance per project keeps each dashboard reporting only its own project.
 * Settings remain application-level, since those are genuinely global user preferences.
 */
@Service(Service.Level.PROJECT)
class TokenSlayerService(private val project: Project) {
    private val log = logger<TokenSlayerService>()
    private val extractor = PsiSymbolExtractor()
    private val skeletonBuilder = SkeletonBuilder()
    private val expander = SymbolExpander()
    private val cache get() = CacheManager.getInstance(project)
    private val settings get() = TokenSlayerSettings.getInstance()

    val excludedFiles = java.util.concurrent.CopyOnWriteArrayList<ExcludedFile>()
    val recentActivity = java.util.Collections.synchronizedList(mutableListOf<CacheEntry>())

    /** Newest-first log of content actually served over MCP. Bounded; not persisted. */
    private val mcpServes = mutableListOf<ServeRecord>()

    companion object {
        val SUPPORTED_EXTENSIONS =
            setOf(
                "java",
                "kt",
                "kts",
                "py",
                "pyi",
                "js",
                "jsx",
                "ts",
                "tsx",
                "mjs",
                "go",
                "rs",
            )

        /** Keeps the realized-savings log bounded; it is a rolling window, not an audit trail. */
        const val MAX_SERVE_RECORDS = 500

        fun getInstance(project: Project): TokenSlayerService = project.service()
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    /**
     * Analyze a single VirtualFile. Returns a FileAnalysisResult or null if skipped.
     * This is the main entry point for per-file analysis.
     */
    fun analyzeFile(
        virtualFile: VirtualFile,
        verbosityOverride: Verbosity? = null,
    ): FileAnalysisResult? {
        val filePath = virtualFile.path
        val ext = virtualFile.extension?.lowercase() ?: return null

        // The shared cache + dashboard stats are keyed to the user's configured verbosity.
        // When a caller (e.g. the MCP tool) requests a different verbosity, compute a
        // fresh skeleton but do NOT read/write the cache, so per-file stats stay accurate
        // and the dashboard doesn't double-count the same file at multiple verbosities.
        val effectiveVerbosity = verbosityOverride ?: settings.verbosity
        val useCache = verbosityOverride == null || verbosityOverride == settings.verbosity

        if (ext !in SUPPORTED_EXTENSIONS) return null
        if (isIgnored(filePath)) return null
        if (virtualFile.length > settings.maxFileSizeKB * 1024L) {
            log.debug("Skipping oversized file: $filePath")
            return null
        }

        val content =
            try {
                String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                log.warn("Cannot read file $filePath", e)
                return null
            }

        // Secrets check
        val secretsScan = SecretsDetector.scan(filePath, content)
        if (secretsScan.hasSecrets) {
            if (excludedFiles.none { it.filePath == filePath }) {
                excludedFiles.add(ExcludedFile(filePath, secretsScan.reasons, secretsScan.severity))
            }
            return FileAnalysisResult(
                filePath = filePath,
                language = ext,
                skeleton = "// [EXCLUDED — contains sensitive data]",
                originalTokens = TokenEstimator.estimate(content),
                skeletonTokens = 0,
                secretsScan = secretsScan,
            )
        }

        // Cache check
        val contentHash = CacheManager.contentHash(content)
        if (useCache) {
            val cached = cache.get(contentHash)
            if (cached != null) {
                addToRecent(cached)
                return FileAnalysisResult(
                    filePath = filePath,
                    language = cached.language,
                    skeleton = cached.skeleton,
                    originalTokens = cached.originalTokens,
                    skeletonTokens = cached.skeletonTokens,
                    secretsScan = secretsScan,
                    fromCache = true,
                )
            }
        }

        // PSI extraction
        val psiFile =
            com.intellij.openapi.application.ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> {
                PsiManager.getInstance(project).findFile(virtualFile)
            } ?: return null

        val language = psiFile.language.id
        val symbols =
            try {
                extractor.extract(psiFile)
            } catch (e: Exception) {
                log.warn("Symbol extraction failed for $filePath", e)
                return null
            }

        // Apply language compactor
        val compactor = CompactorFactory.forLanguage(language)
        val refinedSymbols = compactor?.refineSymbols(symbols, content) ?: symbols

        val totalLines = content.lines().size
        val skeleton = skeletonBuilder.build(refinedSymbols, filePath, totalLines, effectiveVerbosity)

        // Estimate both sides with the SAME language-aware basis so the reduction figure is
        // honest. Previously the original used estimateForLanguage() while the skeleton used
        // the raw estimate(), which applied the language multiplier to one side only and
        // skewed the reported savings.
        val originalTokens = TokenEstimator.estimateForLanguage(content, language)
        val skeletonTokens = TokenEstimator.estimateForLanguage(skeleton, language)

        val entry =
            CacheEntry(
                skeleton = skeleton,
                originalTokens = originalTokens,
                skeletonTokens = skeletonTokens,
                contentHash = contentHash,
                language = language,
                filePath = filePath,
            )
        if (useCache) {
            cache.put(entry)
            addToRecent(entry)
        }

        log.info("Analyzed $filePath: ${entry.reductionPct}% reduction (${entry.originalTokens} → ${entry.skeletonTokens} tokens)")

        return FileAnalysisResult(
            filePath = filePath,
            language = language,
            skeleton = skeleton,
            originalTokens = originalTokens,
            skeletonTokens = skeletonTokens,
            secretsScan = secretsScan,
        )
    }

    /**
     * Get the cached skeleton for a file, or null if not yet analyzed.
     */
    fun getCachedSkeleton(filePath: String): String? = cache.getEntryByPath(filePath)?.skeleton

    /** The full cached entry for a path — needed to report what a serve actually saved. */
    fun getCachedEntry(filePath: String): CacheEntry? = cache.getEntryByPath(filePath)

    // ── Expansion ─────────────────────────────────────────────────────────────

    /**
     * Return the real source of one symbol in [virtualFile].
     *
     * The counterpart to skeleton generation: an assistant orients on the skeleton, then pulls
     * back exactly the implementation it needs instead of re-reading the file, which is where the
     * skeleton's saving would otherwise be lost.
     */
    fun expandSymbol(
        virtualFile: VirtualFile,
        symbolQuery: String,
    ): SymbolExpander.Outcome? {
        val filePath = virtualFile.path
        if (virtualFile.extension?.lowercase() !in SUPPORTED_EXTENSIONS) return null
        if (isIgnored(filePath)) return null

        val content =
            try {
                String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                log.warn("Cannot read file $filePath", e)
                return null
            }

        // Never hand back source from a file we refused to summarize for containing secrets.
        val secretsScan = SecretsDetector.scan(filePath, content)
        if (secretsScan.hasSecrets) return null

        val psiFile =
            com.intellij.openapi.application.ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> {
                PsiManager.getInstance(project).findFile(virtualFile)
            } ?: return null

        val language = psiFile.language.id
        val symbols =
            try {
                extractor.extract(psiFile)
            } catch (e: Exception) {
                log.warn("Symbol extraction failed for $filePath", e)
                return null
            }

        return expander.expand(
            symbols = symbols,
            content = content,
            filePath = filePath,
            query = symbolQuery,
            fileTokens = TokenEstimator.estimateForLanguage(content, language),
            tokenCounter = { TokenEstimator.estimateForLanguage(it, language) },
        )
    }

    // ── Realized savings ──────────────────────────────────────────────────────

    /**
     * Record content actually delivered to an assistant over MCP.
     *
     * Analysis stats count what every scanned file *could* save; these count what was genuinely
     * served. Without this there is no signal that the tools are being called at all.
     */
    fun recordServe(
        filePath: String,
        tool: String,
        originalTokens: Int,
        servedTokens: Int,
    ) {
        synchronized(mcpServes) {
            mcpServes.add(0, ServeRecord(filePath, tool, originalTokens, servedTokens))
            while (mcpServes.size > MAX_SERVE_RECORDS) mcpServes.removeAt(mcpServes.lastIndex)
        }
        log.info("TokenSlayer: served $tool for $filePath ($originalTokens → $servedTokens tokens)")
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun computeStats(): WorkspaceStats {
        val entries = cache.allEntries()
        val totalSaved = entries.sumOf { it.tokensSaved }
        val totalOriginal = entries.sumOf { it.originalTokens }

        val langBreakdown =
            entries
                .groupBy { it.language }
                .mapValues { (lang, es) ->
                    val saved = es.sumOf { it.tokensSaved }
                    val orig = es.sumOf { it.originalTokens }
                    LanguageStat(
                        language = lang,
                        files = es.size,
                        tokensSaved = saved,
                        reductionPct = if (orig > 0) ((saved.toDouble() / orig) * 100).toInt() else 0,
                    )
                }

        val topSavers = entries.sortedByDescending { it.tokensSaved }.take(5)

        val serves = synchronized(mcpServes) { mcpServes.toList() }

        return WorkspaceStats(
            realizedTokensSaved = serves.sumOf { it.tokensSaved },
            realizedOriginalTokens = serves.sumOf { it.originalTokens },
            mcpServeCount = serves.size,
            recentServes = serves.take(8),
            totalTokensSaved = totalSaved,
            totalOriginalTokens = totalOriginal,
            filesAnalyzed = entries.size,
            cacheHits = cache.cacheHits,
            cacheMisses = cache.cacheMisses,
            excludedFiles = excludedFiles.size,
            languageBreakdown = langBreakdown,
            topSavers = topSavers,
            recentActivity = recentActivity.take(10),
            excludedFilesList = excludedFiles.toList(),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addToRecent(entry: CacheEntry) {
        synchronized(recentActivity) {
            recentActivity.removeIf { it.filePath == entry.filePath }
            recentActivity.add(0, entry)
            if (recentActivity.size > 20) recentActivity.removeAt(recentActivity.lastIndex)
        }
    }

    private fun isIgnored(path: String): Boolean {
        val ignoredPaths = settings.ignoredPaths
        return ignoredPaths.any { ignored ->
            path.contains(ignored.trim())
        }
    }
}
