package com.tokenslayer.cache

import com.tokenslayer.types.CacheEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CacheManagerTest {
    private lateinit var cache: CacheManager

    @BeforeEach
    fun setup() {
        cache = CacheManager()
        cache.clear()
    }

    private fun makeEntry(
        path: String,
        hash: String,
        original: Int = 1000,
        skeleton: Int = 100,
    ) = CacheEntry(
        skeleton = "// skeleton for $path",
        originalTokens = original,
        skeletonTokens = skeleton,
        contentHash = hash,
        language = "java",
        filePath = path,
    )

    @Test fun `put and get entry`() {
        val entry = makeEntry("/src/Foo.java", "abc123")
        cache.put(entry)
        val result = cache.get("abc123")
        assertNotNull(result)
        assertEquals("/src/Foo.java", result!!.filePath)
    }

    @Test fun `returns null for cache miss`() {
        val result = cache.get("nonexistent_hash")
        assertNull(result)
    }

    @Test fun `increments hit and miss counters`() {
        val entry = makeEntry("/src/Bar.java", "hash1")
        cache.put(entry)

        cache.get("hash1") // hit
        cache.get("hash1") // hit
        cache.get("unknown") // miss

        assertEquals(2, cache.cacheHits)
        assertEquals(1, cache.cacheMisses)
    }

    @Test fun `invalidate removes entry by file path`() {
        val entry = makeEntry("/src/Baz.java", "hash2")
        cache.put(entry)
        cache.invalidate("/src/Baz.java")
        assertNull(cache.get("hash2"))
    }

    @Test fun `clear removes all entries and resets counters`() {
        cache.put(makeEntry("/a.java", "h1"))
        cache.put(makeEntry("/b.java", "h2"))
        cache.get("h1")
        cache.clear()

        assertEquals(0, cache.size)
        assertEquals(0, cache.cacheHits)
        assertEquals(0, cache.cacheMisses)
    }

    @Test fun `allEntries returns all cached items`() {
        cache.put(makeEntry("/x.java", "hx"))
        cache.put(makeEntry("/y.java", "hy"))
        val all = cache.allEntries()
        assertEquals(2, all.size)
    }

    @Test fun `CacheEntry computes reduction correctly`() {
        val entry = makeEntry("/z.java", "hz", original = 1000, skeleton = 50)
        assertEquals(950, entry.tokensSaved)
        assertEquals(95, entry.reductionPct)
    }

    @Test fun `contentHash is deterministic`() {
        val h1 = CacheManager.contentHash("hello world")
        val h2 = CacheManager.contentHash("hello world")
        assertEquals(h1, h2)
    }

    @Test fun `contentHash differs for different content`() {
        val h1 = CacheManager.contentHash("content A")
        val h2 = CacheManager.contentHash("content B")
        assertNotEquals(h1, h2)
    }

    // ── Path index (added with getEntryByPath) ───────────────────────────────

    @Test fun `getEntryByPath finds an entry by its file path`() {
        cache.put(makeEntry("/src/Foo.java", "abc123"))
        assertEquals("abc123", cache.getEntryByPath("/src/Foo.java")?.contentHash)
        assertNull(cache.getEntryByPath("/src/Nope.java"))
    }

    @Test fun `re-caching a changed file replaces the old revision`() {
        // Same path, new content hash: the stale row must go, or the cache accumulates one
        // entry per historical revision and the dashboard counts the file repeatedly.
        cache.put(makeEntry("/src/Foo.java", "hash-v1"))
        cache.put(makeEntry("/src/Foo.java", "hash-v2"))
        assertEquals(1, cache.size)
        assertEquals("hash-v2", cache.getEntryByPath("/src/Foo.java")?.contentHash)
        assertNull(cache.get("hash-v1"))
    }

    @Test fun `invalidate clears the path index too`() {
        cache.put(makeEntry("/src/Foo.java", "abc123"))
        cache.invalidate("/src/Foo.java")
        assertNull(cache.getEntryByPath("/src/Foo.java"))
        assertNull(cache.get("abc123"))
        assertEquals(0, cache.size)
    }

    @Test fun `clear empties the path index too`() {
        cache.put(makeEntry("/src/Foo.java", "abc123"))
        cache.put(makeEntry("/src/Bar.java", "def456"))
        cache.clear()
        assertNull(cache.getEntryByPath("/src/Foo.java"))
        assertNull(cache.getEntryByPath("/src/Bar.java"))
    }

    @Test fun `LRU eviction does not leave a dangling path index entry`() {
        // Overfill past the default cap so the eldest rows are evicted, then confirm the index
        // agrees with the map: a stale index entry would resolve to null and look like a bug.
        val cap = CacheManager.DEFAULT_MAX_ENTRIES
        repeat(cap + 25) { i -> cache.put(makeEntry("/src/F$i.java", "hash$i")) }
        assertEquals(cap, cache.size)
        for (i in 0 until cap + 25) {
            val byPath = cache.getEntryByPath("/src/F$i.java")
            if (byPath != null) assertEquals("hash$i", byPath.contentHash)
        }
        // The earliest entries are the ones that should have gone.
        assertNull(cache.getEntryByPath("/src/F0.java"))
        assertNotNull(cache.getEntryByPath("/src/F${cap + 24}.java"))
    }
}
