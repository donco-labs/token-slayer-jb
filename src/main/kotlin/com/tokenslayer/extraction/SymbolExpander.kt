package com.tokenslayer.extraction

import com.tokenslayer.types.ExpandedSymbol
import com.tokenslayer.types.StructuralSymbol

/**
 * Resolves a symbol name to its real source text — the inverse of [SkeletonBuilder].
 *
 * Deliberately free of PSI: it works from already-extracted [StructuralSymbol]s plus the file
 * content, which keeps it unit-testable and lets it run off a cached extraction.
 */
class SymbolExpander {
    /** A symbol matched by a query, carrying the dotted path it was reached by. */
    data class Match(
        val symbol: StructuralSymbol,
        val qualifiedName: String,
    )

    sealed interface Outcome {
        data class Found(val expanded: ExpandedSymbol) : Outcome

        /** Several symbols share the queried name; the caller should re-ask with a qualified one. */
        data class Ambiguous(val candidates: List<String>) : Outcome

        /** No match. [available] lets an assistant correct itself without reading the file. */
        data class NotFound(val available: List<String>) : Outcome
    }

    /** Depth-first flatten, recording each symbol's dotted path (`Outer.inner`). */
    fun flatten(
        symbols: List<StructuralSymbol>,
        prefix: String = "",
    ): List<Match> {
        val out = mutableListOf<Match>()
        for (s in symbols) {
            val qualified = if (prefix.isEmpty()) s.name else "$prefix.${s.name}"
            out.add(Match(s, qualified))
            if (s.children.isNotEmpty()) out.addAll(flatten(s.children, qualified))
        }
        return out
    }

    /**
     * Find every symbol matching [query], which may be a bare name (`doThing`) or a dotted or
     * `#`-separated path (`MyClass.doThing`, `MyClass#doThing`).
     *
     * An exact qualified match wins outright. That resolves two cases that would otherwise be
     * reported as ambiguous for no good reason: `Foo` against its own nested `Foo.Foo`, and a
     * bare name that names a top-level symbol while also occurring as a member elsewhere. The
     * caller echoes the qualified name it resolved to, so a wrong guess is visible rather than
     * silent.
     */
    fun findCandidates(
        symbols: List<StructuralSymbol>,
        query: String,
    ): List<Match> {
        val needle = query.trim().replace('#', '.')
        if (needle.isEmpty()) return emptyList()
        val all = flatten(symbols)

        all.firstOrNull { it.qualifiedName == needle }?.let { return listOf(it) }

        return all.filter { m ->
            m.symbol.name == needle || m.qualifiedName.endsWith(".$needle")
        }
    }

    /**
     * Expand [query] against [content]. [fileTokens] is the whole-file token count, carried
     * through so callers can report what serving this instead of the file saved.
     */
    fun expand(
        symbols: List<StructuralSymbol>,
        content: String,
        filePath: String,
        query: String,
        fileTokens: Int,
        tokenCounter: (String) -> Int,
    ): Outcome {
        val candidates = findCandidates(symbols, query)
        when {
            candidates.isEmpty() ->
                return Outcome.NotFound(flatten(symbols).map { it.qualifiedName }.sorted())
            candidates.size > 1 ->
                return Outcome.Ambiguous(candidates.map { it.qualifiedName }.sorted())
        }

        val match = candidates.single()
        val lines = content.lines()
        // lineRange is 0-based inclusive (PsiSymbolExtractor uses Document.getLineNumber).
        // Clamp rather than trust it: the content may have been edited since extraction.
        val start = match.symbol.lineRange.first.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val end = match.symbol.lineRange.last.coerceIn(start, (lines.size - 1).coerceAtLeast(0))
        val source = lines.subList(start, end + 1).joinToString("\n")

        return Outcome.Found(
            ExpandedSymbol(
                name = match.symbol.name,
                qualifiedName = match.qualifiedName,
                kind = match.symbol.kindLabel,
                filePath = filePath,
                startLine = start + 1,
                endLine = end + 1,
                source = source,
                sourceTokens = tokenCounter(source),
                fileTokens = fileTokens,
            ),
        )
    }
}
