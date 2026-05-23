package com.tokenslayer.ui.inlay

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.*
import com.tokenslayer.cache.CacheManager
import com.tokenslayer.settings.TokenSlayerSettings
import javax.swing.JPanel

/**
 * Inline inlay hints: places ⚡ ~N lines → ~M lines skeleton above each class/function.
 * The JetBrains equivalent of VS Code's CodeLens provider.
 */
@Suppress("UnstableApiUsage")
class TokenSlayerInlayProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("tokenslayer.inlay")
    override val name: String = "TokenSlayer skeleton hints"
    override val previewText: String = "class MyService { ... }"

    override fun createSettings(): NoSettings = NoSettings()

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (!TokenSlayerSettings.getInstance().enableInlayHints) return null
        return TokenSlayerInlayCollector(file, editor)
    }

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener) = JPanel()
        }
}

@Suppress("UnstableApiUsage")
private class TokenSlayerInlayCollector(
    private val file: PsiFile,
    private val editor: Editor,
) : InlayHintsCollector {
    private val cache = CacheManager.getInstance()
    private val factory = PresentationFactory(editor)
    private val virtualFile = file.virtualFile

    override fun collect(
        element: PsiElement,
        editor: Editor,
        sink: InlayHintsSink,
    ): Boolean {
        if (!isAnnotatableElement(element)) return true
        if (!element.isValid) return true

        // Only annotate if file is cached (avoid triggering analysis from hint)
        val cachedEntry =
            cache.allEntries()
                .firstOrNull { it.filePath == virtualFile?.path }
                ?: return true

        val elementLines = calculateElementLines(element) ?: return true
        if (elementLines < 10) return true // too small to annotate

        val skeletonLines = estimateSkeletonLines(element)
        val reductionPct = ((1.0 - skeletonLines.toDouble() / elementLines) * 100).toInt().coerceAtLeast(0)
        if (reductionPct < 20) return true // not worth showing

        val hintText = " ⚡ ~$elementLines → ~$skeletonLines lines  ($reductionPct% skeleton)"
        val presentation: InlayPresentation = factory.smallText(hintText)
        val textRange = element.textRange ?: return true

        sink.addBlockElement(
            offset = textRange.startOffset,
            relatesToPrecedingText = false,
            showAbove = true,
            priority = 0,
            presentation = presentation,
        )

        return true
    }

    private fun isAnnotatableElement(element: PsiElement): Boolean =
        (element is PsiClass && element.parent is PsiFile) || element is PsiMethod

    private fun calculateElementLines(element: PsiElement): Int? {
        val doc =
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getDocument(virtualFile ?: return null) ?: return null
        val range = element.textRange ?: return null
        val startLine = doc.getLineNumber(range.startOffset)
        val endLine = doc.getLineNumber(range.endOffset)
        return endLine - startLine + 1
    }

    private fun estimateSkeletonLines(element: PsiElement): Int =
        when (element) {
            is PsiClass -> 1 + element.methods.size + element.fields.size / 2
            is PsiMethod -> 1
            else -> 1
        }.coerceAtLeast(1)
}
