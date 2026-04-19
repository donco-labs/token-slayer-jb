package com.tokenslayer.cache

import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.tokenslayer.types.CacheEntry
import java.security.MessageDigest

/**
 * LRU cache for structural skeletons.
 * Persisted across IDE sessions via PersistentStateComponent.
 * Direct equivalent of VS Code's cacheManager.ts.
 */
@Service(Service.Level.APP)
@State(
    name = "TokenSlayerCache",
    storages = [Storage("tokenslayer-cache.xml", roamingType = RoamingType.DISABLED)],
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
        var maxSize: Int = 500,
    )

    private var state = CacheState()

    // In-memory LRU map (access-ordered LinkedHashMap)
    private val lruMap: LinkedHashMap<String, CacheEntry> = object :
        LinkedHashMap<String, CacheEntry>(state.maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > state.maxSize
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
    fun get(contentHash: String): CacheEntry? {
        val entry = lruMap[contentHash]
        return if (entry != null) {
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
    fun put(entry: CacheEntry) {
        lruMap[entry.contentHash] = entry
        log.debug("Cached skeleton for ${entry.filePath} (${entry.reductionPct}% reduction)")
    }

    /**
     * Invalidate the cache entry for the given file path.
     */
    fun invalidate(filePath: String) {
        val removed = lruMap.entries.removeIf { it.value.filePath == filePath }
        if (removed) log.debug("Invalidated cache for $filePath")
    }

    /**
     * Clear the entire cache.
     */
    fun clear() {
        lruMap.clear()
        cacheHits = 0
        cacheMisses = 0
        log.info("Cache cleared")
    }

    /** All current cache entries (for dashboard display). */
    fun allEntries(): List<CacheEntry> = lruMap.values.toList()

    /** Current cache size. */
    val size: Int get() = lruMap.size

    // ── Content Hashing ──────────────────────────────────────────────────────

    companion object {
        fun contentHash(content: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun getInstance(): CacheManager = service()
    }

    // ── PersistentStateComponent ─────────────────────────────────────────────

    override fun getState(): CacheState {
        // Persist current in-memory LRU to state
        val persistedEntries = lruMap.entries.associate { (k, v) ->
            k to SerializableEntry(
                skeleton = v.skeleton,
                originalTokens = v.originalTokens,
                skeletonTokens = v.skeletonTokens,
                contentHash = v.contentHash,
                language = v.language,
                filePath = v.filePath,
                timestamp = v.timestamp,
            )
        }
        return state.copy(entries = persistedEntries.toMutableMap())
    }

    override fun loadState(state: CacheState) {
        this.state = state
        // Restore in-memory LRU from persisted state
        lruMap.clear()
        state.entries.forEach { (hash, se) ->
            lruMap[hash] = CacheEntry(
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
