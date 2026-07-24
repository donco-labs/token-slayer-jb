package com.tokenslayer.ui.inlay

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.settings.TokenSlayerSettings

/**
 * Inline inlay hints: appends `⚡ ~N → ~M lines (P% skeleton)` at the end of each
 * class/function declaration line. The JetBrains equivalent of VS Code's CodeLens.
 *
 * Uses the **stable** declarative inlay API (`codeInsight.declarativeInlayProvider`),
 * not the deprecated experimental `InlayHintsProvider`. This clears the experimental-API
 * warnings the Plugin Verifier reported and is the forward-compatible API.
 *
 * The provider is intentionally **language-agnostic**: it references only platform PSI
 * (`PsiElement` / `PsiNamedElement`), never Java-specific classes, so it loads in
 * PyCharm/WebStorm/GoLand and works for every supported language.
 */
class TokenSlayerInlayProvider : InlayHintsProvider {
    override fun createCollector(
        file: PsiFile,
        editor: Editor,
    ): InlayHintsCollector? {
        if (!TokenSlayerSettings.getInstance().enableInlayHints) return null
        return TokenSlayerInlayCollector(file, editor)
    }
}

private class TokenSlayerInlayCollector(
    file: PsiFile,
    private val editor: Editor,
) : SharedBypassCollector {
    private val cache = CacheManager.getInstance(file.project)
    private val virtualFile = file.virtualFile

    override fun collectFromElement(
        element: PsiElement,
        sink: InlayTreeSink,
    ) {
        if (!element.isValid) return
        if (!isStructuralElement(element)) return

        // Only annotate if the file has already been analyzed and cached
        // (avoid triggering analysis from a rendering pass).
        val path = virtualFile?.path ?: return
        if (cache.allEntries().none { it.filePath == path }) return

        val elementLines = calculateElementLines(element) ?: return
        if (elementLines < 10) return // too small to annotate

        val skeletonLines = estimateSkeletonLines(element)
        val reductionPct = ((1.0 - skeletonLines.toDouble() / elementLines) * 100).toInt().coerceAtLeast(0)
        if (reductionPct < 20) return // not worth showing

        val startOffset = element.textRange?.startOffset ?: return
        val line = editor.document.getLineNumber(startOffset)
        val hintText = "⚡ ~$elementLines → ~$skeletonLines lines ($reductionPct% skeleton)"

        // Note: this addPresentation overload is the one available at our sinceBuild (241).
        // It was deprecated in 2024.2+, but the replacement doesn't exist in 241, so we keep
        // this — the Plugin Verifier confirms it stays Compatible across all supported builds.
        sink.addPresentation(EndOfLinePosition(line), hasBackground = true) {
            text(hintText)
        }
    }

    /**
     * Heuristically decide whether a PSI node is a class/function-like declaration,
     * based on its node type name. Works across languages without any hard
     * dependency on a specific language plugin.
     */
    private fun isStructuralElement(element: PsiElement): Boolean {
        val named = element as? PsiNamedElement ?: return false
        if (named.name.isNullOrBlank()) return false
        val typeName = element.javaClass.simpleName.lowercase()
        return STRUCTURAL_MARKERS.any { it in typeName }
    }

    private fun calculateElementLines(element: PsiElement): Int? {
        val range = element.textRange ?: return null
        val doc = editor.document
        if (range.endOffset > doc.textLength) return null
        val startLine = doc.getLineNumber(range.startOffset)
        val endLine = doc.getLineNumber(range.endOffset)
        return endLine - startLine + 1
    }

    /** Generic skeleton-size estimate: one line for the signature + one per named member. */
    private fun estimateSkeletonLines(element: PsiElement): Int {
        val namedChildren = element.children.count { it is PsiNamedElement && !it.name.isNullOrBlank() }
        return (1 + namedChildren).coerceAtLeast(1)
    }

    private companion object {
        val STRUCTURAL_MARKERS =
            listOf(
                "class",
                "interface",
                "object",
                "struct",
                "trait",
                "impl",
                "enum",
                "function",
                "method",
                "fun",
            )
    }
}
