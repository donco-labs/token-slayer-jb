package com.tokenslayer.compaction

import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind

class JavaCompactor : Compactor {
    override val supportedLanguages = setOf("java")

    override fun refineSymbols(symbols: List<StructuralSymbol>, fileContent: String): List<StructuralSymbol> =
        symbols.map { refine(it) }

    private fun refine(symbol: StructuralSymbol): StructuralSymbol {
        val cleaned = cleanSignature(symbol.signatureLine)
        return symbol.copy(
            signatureLine = cleaned,
            children = symbol.children.map { refine(it) },
        )
    }

    override fun cleanSignature(raw: String): String {
        return raw
            .replace(Regex("""\s*\{.*"""), "")       // strip body start
            .replace(Regex("""\s*throws\s+\w+"""), "") // strip throws clause (optionally keep)
            .trim()
    }
}
