package com.tokenslayer.extraction

import com.tokenslayer.types.FindMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the pure ranking/formatting half of symbol search. [SymbolFinder] itself needs a live
 * PSI index and isn't unit tested, matching how PsiSymbolExtractor is exercised only in a real
 * IDE — see the note on [SymbolFinderFormat].
 */
class SymbolFinderFormatTest {
    private fun match(
        name: String,
        filePath: String = "/src/$name.kt",
        line: Int = 1,
        kindLabel: String = "method",
        signature: String = "$name()",
    ) = FindMatch(name, kindLabel, filePath, line, signature)

    // ── matchRank ───────────────────────────────────────────────────────────

    @Test fun `exact name match ranks above a prefix match`() {
        assertTrue(SymbolFinderFormat.matchRank("run", "run") < SymbolFinderFormat.matchRank("runAll", "run"))
    }

    @Test fun `prefix match ranks above a plain substring match`() {
        assertTrue(SymbolFinderFormat.matchRank("runAll", "run") < SymbolFinderFormat.matchRank("preRun", "run"))
    }

    @Test fun `matchRank is case-insensitive`() {
        assertEquals(SymbolFinderFormat.matchRank("Run", "run"), SymbolFinderFormat.matchRank("run", "run"))
    }

    // ── rank ────────────────────────────────────────────────────────────────

    @Test fun `rank orders exact match before prefix before substring`() {
        val matches = listOf(match("xfooy"), match("fooBar"), match("foo"))
        val ranked = SymbolFinderFormat.rank(matches, "foo").map { it.name }
        assertEquals(listOf("foo", "fooBar", "xfooy"), ranked)
    }

    @Test fun `rank breaks ties by shorter name first`() {
        val matches = listOf(match("fooBarBaz"), match("fooBar"))
        val ranked = SymbolFinderFormat.rank(matches, "foo").map { it.name }
        assertEquals(listOf("fooBar", "fooBarBaz"), ranked)
    }

    // ── format ──────────────────────────────────────────────────────────────

    @Test fun `format reports no matches for an empty list`() {
        assertEquals("No matches.", SymbolFinderFormat.format(emptyList()))
    }

    @Test fun `format renders a citable file colon line coordinate`() {
        val found =
            match(
                "validateToken",
                filePath = "/repo/src/Auth.kt",
                line = 42,
                signature = "fun validateToken(t: String): Boolean",
            )
        val text = SymbolFinderFormat.format(listOf(found))
        assertTrue("Auth.kt:42 — method fun validateToken(t: String): Boolean" in text)
    }

    @Test fun `format uses the basename, not the full path`() {
        val text = SymbolFinderFormat.format(listOf(match("Foo", filePath = "/a/b/c/Foo.kt")))
        assertTrue("/a/b/c/" !in text)
        assertTrue("Foo.kt:1" in text)
    }

    @Test fun `format lists multiple matches one per line`() {
        val text = SymbolFinderFormat.format(listOf(match("a"), match("b")))
        assertEquals(2, text.lines().size)
    }

    @Test fun `format prepends a header line when given`() {
        val text = SymbolFinderFormat.format(listOf(match("a")), header = "References to 'a' (1 found):")
        val lines = text.lines()
        assertEquals("References to 'a' (1 found):", lines.first())
        assertEquals(2, lines.size)
    }

    @Test fun `format omits the header for an empty list`() {
        assertEquals("No matches.", SymbolFinderFormat.format(emptyList(), header = "References to 'x':"))
    }
}
