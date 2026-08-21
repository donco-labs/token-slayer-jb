package com.tokenslayer.yaml

/**
 * A pragmatic, hand-rolled parser for block-style YAML — the style essentially every Kubernetes
 * manifest, GitHub Actions workflow, Helm values file, and docker-compose file is written in.
 * PSI-free by design (see SkeletonBuilder / SymbolExpander for the same choice on the code side):
 * the YAML language plugin isn't bundled with every JetBrains IDE, and even where it is, PSI
 * node classification has no notion of "this is a mapping" the way it has of "this is a class".
 *
 * Deliberately not a full YAML 1.2 implementation: flow collections (`{a: 1}`, `[1, 2]`) are kept
 * as opaque scalar text rather than deep-parsed, block scalars (`|`, `>`) are recognized but not
 * content-preserved, and anchors/aliases/tags are left as literal text inside whatever token they
 * appear in. Every one of those degrades to "less compact" rather than to a wrong parse or a
 * crash, the same tolerance PsiSymbolExtractor applies to a PSI shape it doesn't recognize.
 */
class YamlParser {
    fun parse(content: String): List<YamlDocument> {
        val lines = content.lines()

        // Document boundaries are lines that are exactly `---`. A leading `---` before any
        // content just produces an empty first slice, dropped below like any other empty one.
        val boundaries = mutableListOf(-1)
        lines.forEachIndexed { i, line -> if (line.trim() == "---") boundaries.add(i) }
        boundaries.add(lines.size)

        val documents = mutableListOf<YamlDocument>()
        for (b in 0 until boundaries.size - 1) {
            val start = boundaries[b] + 1
            val end = boundaries[b + 1] // exclusive
            if (start >= end) continue
            val firstContentLine = (start until end).firstOrNull { isContent(lines[it]) } ?: continue
            val indent = indentOf(lines[firstContentLine])
            val (nodes, _) = parseBlock(lines, firstContentLine, end, indent)
            if (nodes.isEmpty()) continue
            documents.add(YamlDocument(documents.size, start..(end - 1), nodes))
        }
        return documents
    }

    private fun isContent(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && !t.startsWith("#") && t != "---" && t != "..."
    }

    private fun indentOf(line: String): Int {
        val i = line.indexOfFirst { it != ' ' }
        return if (i < 0) line.length else i
    }

    /** Parses sibling items at exactly [indent], starting at [start]. Returns (nodes, nextIndex). */
    private fun parseBlock(
        lines: List<String>,
        start: Int,
        end: Int,
        indent: Int,
    ): Pair<List<YamlNode>, Int> {
        val nodes = mutableListOf<YamlNode>()
        var i = start
        while (i < end) {
            val line = lines[i]
            if (!isContent(line)) {
                i++
                continue
            }
            val lineIndent = indentOf(line)
            if (lineIndent != indent) break // dedent or unexpected indent — this block is done

            val trimmed = line.substring(lineIndent)
            i =
                when {
                    trimmed == "-" || trimmed.startsWith("- ") -> parseListItem(lines, i, end, lineIndent, nodes)
                    findKeyColon(trimmed) != null -> parseMapEntry(lines, i, end, lineIndent, nodes)
                    else -> {
                        nodes.add(YamlNode(key = null, kind = YamlKind.SCALAR, scalarValue = trimmed, lineRange = i..i))
                        i + 1
                    }
                }
        }
        return nodes to i
    }

    private fun parseListItem(
        lines: List<String>,
        i: Int,
        end: Int,
        dashIndent: Int,
        into: MutableList<YamlNode>,
    ): Int {
        val stripped = lines[i].substring(dashIndent) // e.g. "- name: api" or "-"
        val afterDash = stripped.removePrefix("-").let { if (it.startsWith(" ")) it.substring(1) else it }
        return when {
            afterDash.isBlank() -> {
                val childIndent = firstIndentAfter(lines, i + 1, end, minIndent = dashIndent + 1)
                if (childIndent == null) {
                    into.add(YamlNode(key = null, kind = YamlKind.SCALAR, scalarValue = "", lineRange = i..i))
                    i + 1
                } else {
                    val (children, next) = parseBlock(lines, i + 1, end, childIndent)
                    into.add(YamlNode(key = null, kind = inferredKind(children), lineRange = i..(next - 1), children = children))
                    next
                }
            }
            findKeyColon(afterDash) != null -> {
                // "- key: value": the rest of this line IS the first entry of a map, and any
                // further lines aligned to where afterDash begins are that SAME map's siblings.
                val valueColumn = dashIndent + (stripped.length - afterDash.length)
                val (firstEntry, firstEnd) = parseMapEntryText(lines, i, afterDash, valueColumn, end)
                val (restEntries, next) = parseBlock(lines, firstEnd, end, valueColumn)
                into.add(
                    YamlNode(
                        key = null,
                        kind = YamlKind.MAP,
                        lineRange = i..(next - 1),
                        children = listOf(firstEntry) + restEntries,
                    ),
                )
                next
            }
            else -> {
                into.add(YamlNode(key = null, kind = YamlKind.SCALAR, scalarValue = afterDash, lineRange = i..i))
                i + 1
            }
        }
    }

    private fun parseMapEntry(
        lines: List<String>,
        i: Int,
        end: Int,
        keyIndent: Int,
        into: MutableList<YamlNode>,
    ): Int {
        val (node, next) = parseMapEntryText(lines, i, lines[i].substring(keyIndent), keyIndent, end)
        into.add(node)
        return next
    }

    /**
     * Parses ONE `key: value` (or `key:` plus a nested block) entry whose key text begins at
     * column [keyColumn] on line [i]. [text] is that line's content from [keyColumn] onward.
     * Shared by a normal map entry and a list item's inline `- key: value` first entry.
     */
    private fun parseMapEntryText(
        lines: List<String>,
        i: Int,
        text: String,
        keyColumn: Int,
        end: Int,
    ): Pair<YamlNode, Int> {
        val colon = findKeyColon(text) ?: return YamlNode(null, YamlKind.SCALAR, i..i, text) to (i + 1)
        val key = text.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'")
        val rest = text.substring(colon + 1).trim()

        return when {
            rest.isEmpty() -> {
                val childIndent = firstIndentAfter(lines, i + 1, end, minIndent = keyColumn + 1)
                if (childIndent == null) {
                    YamlNode(key, YamlKind.SCALAR, i..i, "") to (i + 1)
                } else {
                    val (children, next) = parseBlock(lines, i + 1, end, childIndent)
                    YamlNode(key, inferredKind(children), i..(next - 1), children = children) to next
                }
            }
            rest[0] == '|' || rest[0] == '>' -> {
                val blockEnd = skipBlockScalar(lines, i + 1, end, minIndent = keyColumn + 1)
                YamlNode(key, YamlKind.SCALAR, i..(blockEnd - 1), "<block scalar>") to blockEnd
            }
            else -> YamlNode(key, YamlKind.SCALAR, i..i, rest) to (i + 1)
        }
    }

    /** A block is a MAP if its first item is itself keyed, otherwise a LIST (incl. all-scalar). */
    private fun inferredKind(children: List<YamlNode>): YamlKind = if (children.firstOrNull()?.key != null) YamlKind.MAP else YamlKind.LIST

    /** Indent of the first real content line at or past [minIndent], or null if there isn't one. */
    private fun firstIndentAfter(
        lines: List<String>,
        from: Int,
        end: Int,
        minIndent: Int,
    ): Int? {
        for (idx in from until end) {
            if (!isContent(lines[idx])) continue
            val ind = indentOf(lines[idx])
            return if (ind >= minIndent) ind else null
        }
        return null
    }

    /** Consumes a `|`/`>` block scalar's body: every line at or past [minIndent], blanks included. */
    private fun skipBlockScalar(
        lines: List<String>,
        from: Int,
        end: Int,
        minIndent: Int,
    ): Int {
        var i = from
        while (i < end) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            if (indentOf(line) < minIndent) break
            i++
        }
        return i
    }

    /**
     * Index of the `:` that separates a mapping key from its value, or null if [text] isn't a
     * map entry. A `:` only counts as YAML's key/value separator when followed by whitespace or
     * end-of-line — `http://host:8080` has no space after that colon, so it is correctly left
     * alone. Colons inside a quoted span are skipped so a quoted value can safely contain one.
     */
    private fun findKeyColon(text: String): Int? {
        var inQuote: Char? = null
        for (idx in text.indices) {
            val c = text[idx]
            if (inQuote != null) {
                if (c == inQuote) inQuote = null
                continue
            }
            when (c) {
                '\'', '"' -> inQuote = c
                ':' -> {
                    val next = text.getOrNull(idx + 1)
                    if (next == null || next == ' ') return idx
                }
            }
        }
        return null
    }
}
