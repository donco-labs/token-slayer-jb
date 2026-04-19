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
import com.tokenslayer.settings.TokenSlayerSettings
import com.tokenslayer.types.*
import com.tokenslayer.utils.SecretsDetector
import com.tokenslayer.utils.TokenEstimator

/**
 * Application-level singleton service.
 * Core orchestrator: extracts symbols, builds skeletons, manages cache and stats.
 */
@Service(Service.Level.APP)
class TokenSlayerService {
    private val log = logger<TokenSlayerService>()
    private val extractor = PsiSymbolExtractor()
    private val skeletonBuilder = SkeletonBuilder()
    private val cache get() = CacheManager.getInstance()
    private val settings get() = TokenSlayerSettings.getInstance()

    val excludedFiles = mutableListOf<ExcludedFile>()
    val recentActivity = ArrayDeque<CacheEntry>(20)

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

        fun getInstance(): TokenSlayerService = service()
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    /**
     * Analyze a single VirtualFile. Returns a FileAnalysisResult or null if skipped.
     * This is the main entry point for per-file analysis.
     */
    fun analyzeFile(
        virtualFile: VirtualFile,
        project: Project,
    ): FileAnalysisResult? {
        val filePath = virtualFile.path
        val ext = virtualFile.extension?.lowercase() ?: return null

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

        // PSI extraction
        val psiFile =
            com.intellij.openapi.application.runReadAction {
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
        val verbosity = settings.verbosity
        val skeleton = skeletonBuilder.build(refinedSymbols, filePath, totalLines, verbosity)

        val originalTokens = TokenEstimator.estimateForLanguage(content, language)
        val skeletonTokens = TokenEstimator.estimate(skeleton)

        // Store in cache
        val entry =
            CacheEntry(
                skeleton = skeleton,
                originalTokens = originalTokens,
                skeletonTokens = skeletonTokens,
                contentHash = contentHash,
                language = language,
                filePath = filePath,
            )
        cache.put(entry)
        addToRecent(entry)

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
    fun getCachedSkeleton(filePath: String): String? {
        return cache.allEntries().firstOrNull { it.filePath == filePath }?.skeleton
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

        return WorkspaceStats(
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
        recentActivity.removeIf { it.filePath == entry.filePath }
        recentActivity.addFirst(entry)
        if (recentActivity.size > 20) recentActivity.removeLast()
    }

    private fun isIgnored(path: String): Boolean {
        val ignoredPaths = settings.ignoredPaths
        return ignoredPaths.any { ignored ->
            path.contains(ignored.trim())
        }
    }
}
