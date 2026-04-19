package com.tokenslayer.utils

/**
 * Estimates token count for text content.
 * Uses the GPT-4 approximation of 1 token ≈ 4 characters.
 * Direct port of VS Code's tokenEstimator.ts.
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4.0

    /**
     * Estimate the number of tokens in the given text.
     */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /**
     * Estimate tokens for file content with language-specific adjustments.
     */
    fun estimateForLanguage(text: String, language: String): Int {
        val base = estimate(text)
        // Languages with verbose syntax tend to use more tokens per semantic unit
        val multiplier = when (language.lowercase()) {
            "java" -> 1.1   // Java is verbose
            "xml", "html" -> 1.2
            "json" -> 0.9
            "go" -> 0.95
            "rust" -> 1.0
            "python" -> 0.85  // Python is concise
            "typescript", "javascript" -> 0.95
            "kotlin" -> 0.9
            else -> 1.0
        }
        return (base * multiplier).toInt().coerceAtLeast(1)
    }

    /**
     * Human-readable token count (e.g. "1.2k").
     */
    fun format(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
        tokens >= 1_000 -> "${"%.1f".format(tokens / 1_000.0)}k"
        else -> tokens.toString()
    }
}
