package com.tokenslayer.extraction

import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind
import com.tokenslayer.types.Verbosity

/**
 * Builds a compact textual skeleton from extracted structural symbols.
 * Direct port of VS Code's SkeletonBuilder.ts — same output format, same tree connectors.
 */
class SkeletonBuilder {
    /**
     * Build a skeleton string from a list of top-level symbols.
     */
    fun build(
        symbols: List<StructuralSymbol>,
        filePath: String,
        totalLines: Int,
        verbosity: Verbosity = Verbosity.STANDARD,
    ): String {
        if (symbols.isEmpty()) {
            return "// ${basename(filePath)} — no symbols found"
        }

        val lines = mutableListOf<String>()
        val skeletonLineCount = estimateSkeletonLines(symbols)
        lines.add("// ${basename(filePath)} ($totalLines lines → $skeletonLineCount-line skeleton)")
        lines.add("")

        for (symbol in symbols) {
            buildSymbolLines(symbol, lines, 0, verbosity)
        }

        return lines.joinToString("\n")
    }

    private fun buildSymbolLines(
        symbol: StructuralSymbol,
        lines: MutableList<String>,
        depth: Int,
        verbosity: Verbosity,
    ) {
        val indent = "  ".repeat(depth)
        val tag = lineTag(symbol.lineRange)

        when (symbol.kind) {
            SymbolKind.CLASS, SymbolKind.STRUCT, SymbolKind.OBJECT -> {
                lines.add("$indent${symbol.signatureLine}  $tag")
                buildChildrenLines(symbol.children, lines, depth + 1, verbosity)
                lines.add("")
            }

            SymbolKind.INTERFACE -> {
                lines.add("$indent${symbol.signatureLine}  $tag")
                if (verbosity != Verbosity.MINIMAL) {
                    buildChildrenLines(symbol.children, lines, depth + 1, verbosity)
                }
                lines.add("")
            }

            SymbolKind.ENUM -> {
                lines.add("${indent}enum ${symbol.name}  $tag")
                if (verbosity != Verbosity.MINIMAL) {
                    symbol.children.forEach { member ->
                        lines.add("$indent  ${member.name}  ${lineTag(member.lineRange)}")
                    }
                } else {
                    lines.add("$indent  (${symbol.children.size} members)")
                }
                lines.add("")
            }

            SymbolKind.TRAIT, SymbolKind.IMPL -> {
                lines.add("$indent${symbol.signatureLine}  $tag")
                buildChildrenLines(symbol.children, lines, depth + 1, verbosity)
                lines.add("")
            }

            SymbolKind.FUNCTION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR -> {
                val prefix = treePrefix(depth)
                lines.add("$indent$prefix${symbol.signatureLine}  $tag")
            }

            SymbolKind.PROPERTY, SymbolKind.FIELD -> {
                if (verbosity != Verbosity.MINIMAL) {
                    val prefix = treePrefix(depth)
                    lines.add("$indent$prefix${symbol.signatureLine}  $tag")
                }
            }

            SymbolKind.VARIABLE, SymbolKind.CONSTANT -> {
                if (verbosity == Verbosity.DETAILED) {
                    lines.add("$indent${symbol.signatureLine}  $tag")
                }
            }

            SymbolKind.MODULE, SymbolKind.NAMESPACE -> {
                lines.add("$indent${symbol.signatureLine}  $tag")
                buildChildrenLines(symbol.children, lines, depth + 1, verbosity)
                lines.add("")
            }

            else -> {
                if (verbosity == Verbosity.DETAILED) {
                    lines.add("$indent${symbol.signatureLine}  $tag")
                }
            }
        }
    }

    private fun buildChildrenLines(
        children: List<StructuralSymbol>,
        lines: MutableList<String>,
        depth: Int,
        verbosity: Verbosity,
    ) {
        for (child in children) {
            buildSymbolLines(child, lines, depth, verbosity)
        }
    }

    private fun treePrefix(depth: Int): String = if (depth == 0) "" else "├─ "

    /** `:12` or `:12-45` — same coordinate format tokenslayer_expand uses to cite a symbol. */
    private fun lineTag(range: IntRange): String {
        val start = range.first + 1
        val end = range.last + 1
        return if (start == end) ":$start" else ":$start-$end"
    }

    private fun basename(filePath: String): String = filePath.split("/", "\\").lastOrNull() ?: filePath

    private fun estimateSkeletonLines(symbols: List<StructuralSymbol>): Int {
        var count = 0
        for (symbol in symbols) {
            count += 1
            if (symbol.children.isNotEmpty()) {
                count += estimateSkeletonLines(symbol.children)
            }
        }
        return count
    }
}
