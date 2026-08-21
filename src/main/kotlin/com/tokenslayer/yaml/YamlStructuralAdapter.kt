package com.tokenslayer.yaml

import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind

/**
 * Projects a parsed YAML tree into StructuralSymbols so `tokenslayer_expand` can resolve a
 * dotted path through YAML the exact same way it already resolves one through code.
 *
 * SymbolExpander only ever reads name / lineRange / children — never scalarValue — so nothing it
 * needs is lost by dropping the value here. (The skeleton itself is rendered separately, by
 * YamlSkeletonBuilder, straight from the full YamlNode tree, which is where scalarValue and list
 * homogeneity actually matter.)
 */
object YamlStructuralAdapter {
    /**
     * One top-level symbol per document. A single-document file's own top-level keys/items ARE
     * the list (matching how a code file's top-level symbols are its own classes/functions); a
     * multi-document stream instead gets one symbol per document, named exactly as
     * YamlSkeletonBuilder's index line names it, deduplicated so two same-named resources (e.g.
     * the same Kind.name in two namespaces) both stay reachable.
     */
    fun toStructuralSymbols(documents: List<YamlDocument>): List<StructuralSymbol> {
        if (documents.size == 1) {
            return topLevelSymbols(documents.single().root)
        }
        val seen = HashMap<String, Int>()
        return documents.map { doc ->
            val base = documentName(doc.root)
            val count = seen.merge(base, 1, Int::plus)!!
            documentSymbol(doc, if (count == 1) base else "$base#$count")
        }
    }

    private fun topLevelSymbols(root: List<YamlNode>): List<StructuralSymbol> =
        if (root.firstOrNull()?.key != null) {
            root.map { convert(it, it.key ?: "?") }
        } else {
            root.mapIndexed { i, node -> convert(node, i.toString()) }
        }

    private fun documentSymbol(
        doc: YamlDocument,
        name: String,
    ): StructuralSymbol {
        val isMap = doc.root.firstOrNull()?.key != null
        return StructuralSymbol(
            name = name,
            kind = if (isMap) SymbolKind.YAML_MAP else SymbolKind.YAML_LIST,
            kindLabel = if (isMap) "map" else "list",
            signatureLine = name,
            lineRange = doc.lineRange,
            children = topLevelSymbols(doc.root),
        )
    }

    /** [name] is supplied by the caller rather than read from [node].key: a list item has none. */
    private fun convert(
        node: YamlNode,
        name: String,
    ): StructuralSymbol {
        val kind =
            when (node.kind) {
                YamlKind.MAP -> SymbolKind.YAML_MAP
                YamlKind.LIST -> SymbolKind.YAML_LIST
                YamlKind.SCALAR -> SymbolKind.YAML_SCALAR
            }
        return StructuralSymbol(
            name = name,
            kind = kind,
            kindLabel = kind.name.lowercase(),
            signatureLine = name,
            lineRange = node.lineRange,
            children =
                when (node.kind) {
                    YamlKind.MAP -> node.children.map { child -> convert(child, child.key ?: "?") }
                    YamlKind.LIST -> node.children.mapIndexed { i, child -> convert(child, i.toString()) }
                    YamlKind.SCALAR -> emptyList()
                },
        )
    }
}
