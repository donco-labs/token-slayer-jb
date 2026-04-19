package com.tokenslayer.compaction

import com.tokenslayer.types.StructuralSymbol

class PythonCompactor : Compactor {
    override val supportedLanguages = setOf("python")

    override fun refineSymbols(
        symbols: List<StructuralSymbol>,
        fileContent: String,
    ): List<StructuralSymbol> = symbols.map { refine(it) }

    private fun refine(symbol: StructuralSymbol): StructuralSymbol {
        val cleaned = cleanSignature(symbol.signatureLine)
        return symbol.copy(
            signatureLine = cleaned,
            children = symbol.children.map { refine(it) },
        )
    }

    override fun cleanSignature(raw: String): String {
        return raw
            .replace(Regex(""":\s*$"""), "") // remove trailing colon
            .replace(Regex("""#.*$"""), "") // remove inline comments
            .trim()
    }
}
