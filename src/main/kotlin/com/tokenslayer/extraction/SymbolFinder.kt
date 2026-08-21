package com.tokenslayer.extraction

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.tokenslayer.types.FindMatch
import java.util.concurrent.CancellationException

/**
 * Project-wide symbol search — the counterpart to grep for an assistant that has TokenSlayer's
 * tools available.
 *
 * Backed by the `gotoClassContributor` / `gotoSymbolContributor` extension points that power the
 * IDE's own Go to Class / Go to Symbol. Every language plugin (Kotlin, Python, JS/TS, Go, …)
 * already registers one, so this needs no per-language code — unlike PsiShortNamesCache, whose
 * class/method/field shape is Java's and returns nothing useful for e.g. Python top-level
 * functions.
 */
class SymbolFinder {
    private val log = logger<SymbolFinder>()

    /** Ranked, capped matches for [query] — empty if nothing matched. */
    fun find(
        project: Project,
        query: String,
        limit: Int,
    ): List<FindMatch> {
        if (query.isBlank()) return emptyList()

        // Gather well beyond `limit` before ranking, so a good match a later contributor returns
        // isn't dropped in favor of an earlier contributor's weaker ones.
        val gatherCap = (limit * 5).coerceAtLeast(100)
        val gathered = LinkedHashMap<String, FindMatch>()

        ApplicationManager.getApplication().runReadAction {
            val contributors =
                ChooseByNameContributor.CLASS_EP_NAME.extensionList +
                    ChooseByNameContributor.SYMBOL_EP_NAME.extensionList
            for (contributor in contributors) {
                if (gathered.size >= gatherCap) break
                gatherFrom(contributor, project, query, gatherCap, gathered)
            }
        }

        return SymbolFinderFormat.rank(gathered.values.toList(), query).take(limit)
    }

    private fun gatherFrom(
        contributor: ChooseByNameContributor,
        project: Project,
        query: String,
        gatherCap: Int,
        into: MutableMap<String, FindMatch>,
    ) {
        val names =
            try {
                contributor.getNames(project, false)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("SymbolFinder: ${contributor.javaClass.simpleName} failed to list names", e)
                return
            }

        for (name in names) {
            if (into.size >= gatherCap) return
            if (!name.contains(query, ignoreCase = true)) continue

            val items =
                try {
                    contributor.getItemsByName(name, query, project, false)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("SymbolFinder: ${contributor.javaClass.simpleName} failed for '$name'", e)
                    continue
                }

            for (item in items) {
                if (into.size >= gatherCap) return
                val element = item as? PsiElement ?: continue
                if (!element.isValid) continue
                val vFile = element.containingFile?.virtualFile ?: continue
                val line = lineNumberOf(element) ?: continue
                into.putIfAbsent(
                    "${vFile.path}:$line:$name",
                    FindMatch(
                        name = name,
                        kindLabel = inferKind(element).name.lowercase(),
                        filePath = vFile.path,
                        line = line,
                        signatureLine = item.presentation?.presentableText ?: name,
                    ),
                )
            }
        }
    }
}

/**
 * Pure ranking/formatting for [SymbolFinder] results, split out because — unlike the search
 * itself — it needs no live PSI index and so can be unit tested directly.
 */
object SymbolFinderFormat {
    fun rank(
        matches: List<FindMatch>,
        query: String,
    ): List<FindMatch> =
        matches.sortedWith(
            compareBy(
                { matchRank(it.name, query) },
                { it.name.length },
                { it.filePath },
                { it.line },
            ),
        )

    /** Exact name match ranks above a prefix match, which ranks above any other substring hit. */
    fun matchRank(
        name: String,
        query: String,
    ): Int =
        when {
            name.equals(query, ignoreCase = true) -> 0
            name.startsWith(query, ignoreCase = true) -> 1
            else -> 2
        }

    /** [header], when given, is prepended as its own line — e.g. "References to 'x' (3 found):". */
    fun format(
        matches: List<FindMatch>,
        header: String? = null,
    ): String {
        if (matches.isEmpty()) return "No matches."
        val body =
            matches.joinToString("\n") { m ->
                "${basename(m.filePath)}:${m.line} — ${m.kindLabel} ${m.signatureLine}"
            }
        return if (header != null) "$header\n$body" else body
    }

    private fun basename(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
}
