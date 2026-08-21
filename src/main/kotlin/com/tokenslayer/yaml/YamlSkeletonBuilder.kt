package com.tokenslayer.yaml

/**
 * Renders a compact textual skeleton from parsed YAML — the counterpart to SkeletonBuilder, but
 * for data instead of code.
 *
 * A code skeleton can drop bodies because a signature already conveys the meaning; a YAML value
 * IS the meaning, so dropping it saves nothing and just breaks the file. This compacts along
 * different axes instead: a multi-document stream (a Kubernetes manifest bundle, most commonly)
 * collapses to one line per resource; a list of many same-shaped map items collapses to its
 * first item plus a count; a long scalar (a certificate, an inline script, base64 data) is
 * elided to its length; and nesting past a fixed depth collapses to a bare count rather than
 * being printed in full.
 */
class YamlSkeletonBuilder {
    companion object {
        /** Map-shaped list items only collapse once there are more than this many. */
        private const val HOMOGENEOUS_COLLAPSE_THRESHOLD = 3

        /** Scalar (non-map) list items show in full up to this many before eliding the rest. */
        private const val SCALAR_LIST_SHOW_LIMIT = 10

        /** Scalar values longer than this are elided to their length rather than shown whole. */
        private const val LONG_SCALAR_LIMIT = 100
        private const val LONG_SCALAR_PREVIEW = 60

        /** Beyond this nesting depth, a subtree collapses to a bare count instead of recursing. */
        private const val MAX_DEPTH = 6
    }

    fun build(
        documents: List<YamlDocument>,
        filePath: String,
        totalLines: Int,
    ): String {
        if (documents.isEmpty()) {
            return "// ${basename(filePath)} — no YAML content found"
        }

        val lines = mutableListOf<String>()
        if (documents.size > 1) {
            lines.add("// ${basename(filePath)} ($totalLines lines, ${documents.size} documents)")
            lines.add("")
            documents.forEach { doc -> lines.add(indexLine(doc)) }
            lines.add("")
            lines.add("// tokenslayer_expand(symbol: \"<Kind.name>\") returns one document's full skeleton")
        } else {
            val doc = documents.single()
            lines.add("// ${basename(filePath)} ($totalLines lines)")
            lines.add("")
            if (doc.root.firstOrNull()?.key != null) {
                renderChildren(doc.root, depth = 0, lines)
            } else {
                renderList(doc.root, depth = 0, lines)
            }
        }
        return lines.joinToString("\n")
    }

    private fun indexLine(doc: YamlDocument): String {
        val start = doc.lineRange.first + 1
        val end = doc.lineRange.last + 1
        return "[${doc.index}] ${documentName(doc.root)}  :$start-$end"
    }

    private fun renderChildren(
        nodes: List<YamlNode>,
        depth: Int,
        out: MutableList<String>,
    ) {
        for (node in nodes) renderKeyed(node, depth, out)
    }

    private fun renderKeyed(
        node: YamlNode,
        depth: Int,
        out: MutableList<String>,
    ) {
        val indent = "  ".repeat(depth)
        when (node.kind) {
            YamlKind.SCALAR -> out.add("$indent${node.key}: ${renderScalar(node)}")
            YamlKind.MAP, YamlKind.LIST -> {
                if (depth >= MAX_DEPTH) {
                    out.add("$indent${node.key}: ${collapsedSummary(node)}")
                    return
                }
                out.add("$indent${node.key}:")
                if (node.kind == YamlKind.MAP) renderChildren(node.children, depth + 1, out) else renderList(node.children, depth + 1, out)
            }
        }
    }

    private fun renderList(
        items: List<YamlNode>,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (items.isEmpty()) return
        val indent = "  ".repeat(depth)

        if (items.all { it.kind == YamlKind.MAP } && items.size > HOMOGENEOUS_COLLAPSE_THRESHOLD && sameShape(items)) {
            renderListItem(items[0], depth, out)
            out.add("$indent(+ ${items.size - 1} more, same shape)")
            return
        }

        val showLimit = if (items.all { it.kind == YamlKind.SCALAR }) SCALAR_LIST_SHOW_LIMIT else items.size
        items.take(showLimit).forEach { renderListItem(it, depth, out) }
        if (items.size > showLimit) out.add("$indent(+ ${items.size - showLimit} more)")
    }

    private fun renderListItem(
        item: YamlNode,
        depth: Int,
        out: MutableList<String>,
    ) {
        val indent = "  ".repeat(depth)
        when (item.kind) {
            YamlKind.SCALAR -> out.add("$indent- ${renderScalar(item)}")
            YamlKind.LIST -> {
                out.add("$indent-")
                renderList(item.children, depth + 1, out)
            }
            YamlKind.MAP -> {
                if (item.children.isEmpty()) {
                    out.add("$indent- {}")
                    return
                }
                val first = item.children.first()
                when (first.kind) {
                    YamlKind.SCALAR -> out.add("$indent- ${first.key}: ${renderScalar(first)}")
                    YamlKind.MAP -> {
                        out.add("$indent- ${first.key}:")
                        renderChildren(first.children, depth + 1, out)
                    }
                    YamlKind.LIST -> {
                        out.add("$indent- ${first.key}:")
                        renderList(first.children, depth + 1, out)
                    }
                }
                renderChildren(item.children.drop(1), depth + 1, out)
            }
        }
    }

    private fun renderScalar(node: YamlNode): String {
        val v = node.scalarValue
        if (v == "<block scalar>") {
            val bodyLines = (node.lineRange.last - node.lineRange.first).coerceAtLeast(0)
            return "|…($bodyLines lines)"
        }
        if (v.length > LONG_SCALAR_LIMIT) {
            return "${v.take(LONG_SCALAR_PREVIEW)}…(${v.length} chars)"
        }
        return v
    }

    private fun collapsedSummary(node: YamlNode): String =
        if (node.kind == YamlKind.LIST) "[…${node.children.size} items]" else "{…${node.children.size} keys}"

    /** Map-shaped items are the "same shape" when they share their (unordered) set of keys. */
    private fun sameShape(items: List<YamlNode>): Boolean {
        val firstKeys = items[0].children.map { it.key }.toSet()
        return items.all { it.children.map { c -> c.key }.toSet() == firstKeys }
    }

    private fun basename(filePath: String): String = filePath.substringAfterLast('/').substringAfterLast('\\')
}
