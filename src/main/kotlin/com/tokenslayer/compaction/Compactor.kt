package com.tokenslayer.compaction

import com.tokenslayer.types.StructuralSymbol

/**
 * Interface for language-specific skeleton compactors.
 * Each compactor knows how to generate clean, idiomatic signatures for its language.
 */
interface Compactor {
    /** Returns the language IDs this compactor handles. */
    val supportedLanguages: Set<String>

    /**
     * Post-process or augment symbols extracted by PSI.
     * The compactor can refine signatures, filter noise, or add language-specific symbols.
     */
    fun refineSymbols(symbols: List<StructuralSymbol>, fileContent: String): List<StructuralSymbol>

    /**
     * Clean up a raw signature line into idiomatic form.
     */
    fun cleanSignature(raw: String): String = raw.trim()
}

/** Factory — returns the best compactor for the given language ID. */
object CompactorFactory {
    private val compactors: List<Compactor> = listOf(
        JavaCompactor(),
        KotlinCompactor(),
        PythonCompactor(),
        JavaScriptCompactor(),
        GoCompactor(),
        RustCompactor(),
    )

    fun forLanguage(languageId: String): Compactor? =
        compactors.firstOrNull { languageId.lowercase() in it.supportedLanguages }
}
