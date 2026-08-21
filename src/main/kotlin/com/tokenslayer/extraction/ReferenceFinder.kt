package com.tokenslayer.extraction

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.tokenslayer.types.FindMatch
import com.tokenslayer.types.SymbolKind
import java.util.concurrent.CancellationException

/**
 * Finds usages of an already-resolved symbol project-wide — the other half of "who calls this",
 * which grep can only approximate by matching the name as text and hoping every hit is real
 * rather than a comment, a string, or an unrelated same-named symbol.
 */
class ReferenceFinder {
    private val log = logger<ReferenceFinder>()

    /** Up to [limit] references to [target], each described by its enclosing declaration. */
    fun findReferences(
        target: PsiElement,
        limit: Int,
    ): List<FindMatch> {
        val gathered = mutableListOf<FindMatch>()
        try {
            ApplicationManager.getApplication().runReadAction {
                ReferencesSearch.search(target).forEach { ref ->
                    val element = ref.element
                    if (element.isValid) {
                        val vFile = element.containingFile?.virtualFile
                        val line = lineNumberOf(element)
                        if (vFile != null && line != null) {
                            gathered.add(describe(element, vFile.path, line))
                        }
                    }
                    // Returning false stops the search early once the cap is hit, instead of
                    // materializing every usage of a widely-called symbol just to discard most.
                    gathered.size < limit
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ReferenceFinder: search failed", e)
        }
        return gathered.sortedWith(compareBy({ it.filePath }, { it.line }))
    }

    private fun describe(
        reference: PsiElement,
        filePath: String,
        line: Int,
    ): FindMatch {
        val enclosing = enclosingDeclaration(reference)
        val kind = enclosing?.let { inferKind(it) } ?: SymbolKind.UNKNOWN
        val name = (enclosing as? PsiNamedElement)?.name.orEmpty()
        val signature = (enclosing as? NavigationItem)?.presentation?.presentableText ?: name
        return FindMatch(
            name = name,
            kindLabel = kind.name.lowercase(),
            filePath = filePath,
            line = line,
            signatureLine = signature.ifBlank { "(reference)" },
        )
    }

    /** Nearest enclosing declaration a human would recognize — skips anonymous/synthetic wrappers. */
    private fun enclosingDeclaration(element: PsiElement): PsiElement? {
        var el: PsiElement? = element.parent
        while (el != null) {
            if (el is PsiNamedElement && el.name != null && inferKind(el) != SymbolKind.UNKNOWN) {
                return el
            }
            el = el.parent
        }
        return null
    }
}
