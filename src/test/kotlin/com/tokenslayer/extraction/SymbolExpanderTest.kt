package com.tokenslayer.extraction

import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolExpanderTest {
    private val expander = SymbolExpander()

    /** Local stand-in for assertInstanceOf, which isn't present in the pinned JUnit version. */
    private inline fun <reified T> assertIsType(value: Any?): T {
        assertTrue(value is T) {
            "expected ${T::class.simpleName} but was ${value?.let { it::class.simpleName } ?: "null"}"
        }
        return value as T
    }

    /** Line numbers below refer to this content, 0-based as PsiSymbolExtractor produces them. */
    private val source =
        """
        package demo

        class Greeter {
            fun hello(name: String): String {
                return "hi ${'$'}name"
            }

            fun bye(): String {
                return "bye"
            }
        }

        fun hello(): Unit = Unit
        """.trimIndent()

    private fun sym(
        name: String,
        range: IntRange,
        kind: SymbolKind = SymbolKind.METHOD,
        children: List<StructuralSymbol> = emptyList(),
    ) = StructuralSymbol(
        name = name,
        kind = kind,
        kindLabel = kind.name.lowercase(),
        signatureLine = name,
        lineRange = range,
        children = children,
    )

    /** Greeter spans lines 2..10; hello 3..5; bye 7..9; the top-level hello is line 12. */
    private val symbols =
        listOf(
            sym(
                "Greeter",
                2..10,
                SymbolKind.CLASS,
                listOf(sym("hello", 3..5), sym("bye", 7..9)),
            ),
            sym("hello", 12..12, SymbolKind.FUNCTION),
        )

    private fun expand(query: String) =
        expander.expand(
            symbols = symbols,
            content = source,
            filePath = "/src/Greeter.kt",
            query = query,
            fileTokens = 1000,
            tokenCounter = { it.length / 4 },
        )

    @Test fun `flatten records dotted paths for nested symbols`() {
        val names = expander.flatten(symbols).map { it.qualifiedName }
        assertEquals(listOf("Greeter", "Greeter.hello", "Greeter.bye", "hello"), names)
    }

    @Test fun `expands a nested method to its own lines only`() {
        val found = assertIsType<SymbolExpander.Outcome.Found>(expand("Greeter.bye"))
        assertEquals("Greeter.bye", found.expanded.qualifiedName)
        // 1-based, inclusive — how a human or an LLM cites lines.
        assertEquals(8, found.expanded.startLine)
        assertEquals(10, found.expanded.endLine)
        assertTrue(found.expanded.source.contains("return \"bye\""))
        assertTrue(!found.expanded.source.contains("hi "), "must not bleed into the sibling method")
    }

    @Test fun `a bare name that exactly matches a top-level symbol resolves to it`() {
        // "hello" is both Greeter.hello and a top-level function. The top-level one's qualified
        // name IS "hello", so an unqualified query resolves there rather than erroring — the
        // natural reading, and deterministic. The response header names what was returned, so a
        // caller that wanted the method can see it got something else and re-ask qualified.
        val found = assertIsType<SymbolExpander.Outcome.Found>(expand("hello"))
        assertEquals("hello", found.expanded.qualifiedName)
        assertEquals(13, found.expanded.startLine)
    }

    @Test fun `reports ambiguity when a name occurs in two classes and neither is top-level`() {
        val twoClasses =
            listOf(
                sym("Foo", 0..3, SymbolKind.CLASS, listOf(sym("bar", 1..2))),
                sym("Baz", 5..8, SymbolKind.CLASS, listOf(sym("bar", 6..7))),
            )
        val outcome =
            expander.expand(twoClasses, source, "/src/X.kt", "bar", 1000) { it.length / 4 }
        val amb = assertIsType<SymbolExpander.Outcome.Ambiguous>(outcome)
        assertEquals(listOf("Baz.bar", "Foo.bar"), amb.candidates)
    }

    @Test fun `an exact qualified match wins over the ambiguous bare name`() {
        val found = assertIsType<SymbolExpander.Outcome.Found>(expand("Greeter.hello"))
        assertEquals(4, found.expanded.startLine)
    }

    @Test fun `accepts a hash-separated path`() {
        val found = assertIsType<SymbolExpander.Outcome.Found>(expand("Greeter#bye"))
        assertEquals("Greeter.bye", found.expanded.qualifiedName)
    }

    @Test fun `unknown symbol lists what is available so the caller can correct itself`() {
        val nf = assertIsType<SymbolExpander.Outcome.NotFound>(expand("nope"))
        assertEquals(listOf("Greeter", "Greeter.bye", "Greeter.hello", "hello"), nf.available)
    }

    @Test fun `blank query is treated as not found`() {
        assertIsType<SymbolExpander.Outcome.NotFound>(expand("   "))
    }

    @Test fun `reports the saving against the whole file`() {
        val found = assertIsType<SymbolExpander.Outcome.Found>(expand("Greeter.bye"))
        assertEquals(1000, found.expanded.fileTokens)
        assertTrue(found.expanded.sourceTokens < found.expanded.fileTokens)
        assertEquals(1000 - found.expanded.sourceTokens, found.expanded.tokensSaved)
    }

    @Test fun `clamps a stale range past the end of the file instead of throwing`() {
        // The file may have been edited since extraction, leaving ranges pointing off the end.
        val stale = listOf(sym("ghost", 500..600))
        val outcome =
            expander.expand(stale, source, "/src/Greeter.kt", "ghost", 1000) { it.length / 4 }
        val found = assertIsType<SymbolExpander.Outcome.Found>(outcome)
        assertTrue(found.expanded.endLine <= source.lines().size)
    }

    @Test fun `handles an empty symbol list`() {
        val outcome = expander.expand(emptyList(), source, "/f.kt", "anything", 10) { 1 }
        val nf = assertIsType<SymbolExpander.Outcome.NotFound>(outcome)
        assertTrue(nf.available.isEmpty())
    }
}
