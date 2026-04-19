package com.tokenslayer.compaction

import com.tokenslayer.types.StructuralSymbol

class RustCompactor : Compactor {
    override val supportedLanguages = setOf("rust")

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
            .replace(Regex("""\s*\{.*"""), "")      // strip body
            .replace(Regex("""\s*where\s+.*"""), "")// strip where clause
            .replace(Regex("""\s*//.*"""), "")       // strip comment
            .trim()
    }
}
