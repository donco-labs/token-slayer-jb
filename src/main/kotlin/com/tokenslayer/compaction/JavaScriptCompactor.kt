package com.tokenslayer.compaction

import com.tokenslayer.types.StructuralSymbol

class JavaScriptCompactor : Compactor {
    override val supportedLanguages = setOf("javascript", "typescript", "ecmascript 6", "jsx harmony", "tsx")

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
            .replace(Regex("""\s*\{.*"""), "") // strip body
            .replace(Regex("""\s*=>\s*\{.*"""), "") // strip arrow body
            .replace(Regex("""\s*//.*"""), "") // strip inline comments
            .trim()
    }
}
