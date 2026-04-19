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
}
