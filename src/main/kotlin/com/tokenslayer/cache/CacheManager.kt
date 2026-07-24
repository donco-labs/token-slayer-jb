package com.tokenslayer.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.tokenslayer.settings.TokenSlayerSettings
import com.tokenslayer.types.CacheEntry
import java.security.MessageDigest

/**
 * LRU cache for structural skeletons.
 * Persisted across IDE sessions via PersistentStateComponent.
 * Direct equivalent of VS Code's cacheManager.ts.
 *
 * PROJECT-scoped on purpose. This was previously an application service, which meant every
 * open workspace shared one cache: the dashboard summed stats across all of them, and because
 * entries are keyed by content hash alone, two projects containing an identical file shared a
 * single entry whose `filePath` pointed into whichever project analysed it first — so the other
 * project silently lost its tree badge and skeleton lookups. One cache per project fixes both.
 *
 * Stored in CACHE_FILE (outside the project directory, never shared or roamed) since this is
 * regenerable derived data, not user configuration.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "TokenSlayerCache",
    storages = [Storage(StoragePathMacros.CACHE_FILE)],
)
class CacheManager : PersistentStateComponent<CacheManager.CacheState> {
    private val log = logger<CacheManager>()

    data class SerializableEntry(
        var skeleton: String = "",
        var originalTokens: Int = 0,
        var skeletonTokens: Int = 0,
        var contentHash: String = "",
        var language: String = "",
        var filePath: String = "",
        var timestamp: Long = 0L,
    )

    data class CacheState(
        var entries: MutableMap<String, SerializableEntry> = mutableMapOf(),
        @Deprecated("Superseded by TokenSlayerSettings.cacheMaxEntries; retained for state compatibility.")
        var maxSize: Int = DEFAULT_MAX_ENTRIES,
    )

    private var state = CacheState()

    /**
     * Effective LRU capacity. Reads [TokenSlayerSettings.cacheMaxEntries] on each eviction check
     * so a change in Settings takes effect immediately. Previously the cap was hard-coded to the
     * `CacheState.maxSize` field, which meant the Settings spinner was silently inert. Now scoped
     * per project (see the class note), so this is a per-workspace budget.
     *
     * Falls back to [DEFAULT_MAX_ENTRIES] when no Application is available — the case in pure-JVM
     * unit tests, and in the narrow windows before startup completes or after shutdown begins.
     */
    private val maxEntries: Int
        get() {
            val app = ApplicationManager.getApplication() ?: return DEFAULT_MAX_ENTRIES
            if (app.isDisposed) return DEFAULT_MAX_ENTRIES
            return TokenSlayerSettings.getInstance().cacheMaxEntries
        }

    // In-memory LRU map (access-ordered LinkedHashMap)
    private val lruMap: LinkedHashMap<String, CacheEntry> =
        object :
            LinkedHashMap<String, CacheEntry>(DEFAULT_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > maxEntries
            }
        }

    // ── Stats ────────────────────────────────────────────────────────────────
    var cacheHits: Int = 0
        private set
    var cacheMisses: Int = 0
        private set

    // ── Read / Write ─────────────────────────────────────────────────────────

    /**
     * Get a cached entry by content hash. Returns null on miss.
     */
    fun get(contentHash: String): CacheEntry? =
        synchronized(lruMap) {
            val entry = lruMap[contentHash]
            if (entry != null) {
                cacheHits++
                log.debug("Cache HIT for hash $contentHash")
                entry
            } else {
                cacheMisses++
                log.debug("Cache MISS for hash $contentHash")
                null
            }
        }

    /**
     * Store a skeleton entry in the cache.
     */
    fun put(entry: CacheEntry) =
        synchronized(lruMap) {
            lruMap[entry.contentHash] = entry
            log.debug("Cached skeleton for ${entry.filePath} (${entry.reductionPct}% reduction)")
        }

    /**
     * Invalidate the cache entry for the given file path.
     */
    fun invalidate(filePath: String) =
        synchronized(lruMap) {
            val removed = lruMap.entries.removeIf { it.value.filePath == filePath }
            if (removed) log.debug("Invalidated cache for $filePath")
        }

    /**
     * Clear the entire cache.
     */
    fun clear() =
        synchronized(lruMap) {
            lruMap.clear()
            cacheHits = 0
            cacheMisses = 0
            log.info("Cache cleared")
        }

    /** All current cache entries (for dashboard display). */
    fun allEntries(): List<CacheEntry> = synchronized(lruMap) { lruMap.values.toList() }

    /** Current cache size. */
    val size: Int get() = synchronized(lruMap) { lruMap.size }

    // ── Content Hashing ──────────────────────────────────────────────────────

    companion object {
        /** Initial map sizing and the fallback cap; the live cap comes from Settings. */
        const val DEFAULT_MAX_ENTRIES = 500

        fun contentHash(content: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun getInstance(project: Project): CacheManager = project.service()
    }

    // ── PersistentStateComponent ─────────────────────────────────────────────

    override fun getState(): CacheState =
        synchronized(lruMap) {
            // Persist current in-memory LRU to state
            val persistedEntries =
                lruMap.entries.associate { (k, v) ->
                    k to
                        SerializableEntry(
                            skeleton = v.skeleton,
                            originalTokens = v.originalTokens,
                            skeletonTokens = v.skeletonTokens,
                            contentHash = v.contentHash,
                            language = v.language,
                            filePath = v.filePath,
                            timestamp = v.timestamp,
                        )
                }
            state.copy(entries = persistedEntries.toMutableMap())
        }

    override fun loadState(state: CacheState) {
        this.state = state
        // Restore in-memory LRU from persisted state
        synchronized(lruMap) {
            lruMap.clear()
            state.entries.forEach { (hash, se) ->
                lruMap[hash] =
                    CacheEntry(
                        skeleton = se.skeleton,
                        originalTokens = se.originalTokens,
                        skeletonTokens = se.skeletonTokens,
                        contentHash = se.contentHash,
                        language = se.language,
                        filePath = se.filePath,
                        timestamp = se.timestamp,
                    )
            }
            log.info("Restored ${lruMap.size} cached entries from disk")
        }
    }
}
