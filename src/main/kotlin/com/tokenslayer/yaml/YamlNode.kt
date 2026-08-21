package com.tokenslayer.yaml

/** What kind of value a [YamlNode] holds. */
enum class YamlKind { MAP, LIST, SCALAR }

/**
 * One YAML value — a mapping, a sequence, or a scalar — with its 0-based source line range
 * (same convention as StructuralSymbol / PsiSymbolExtractor: end-inclusive, from Document
 * line numbers).
 *
 * [key] is the mapping key this node was found under, or null for a list item or a document
 * root node. [scalarValue] is set only when [kind] is SCALAR.
 */
data class YamlNode(
    val key: String?,
    val kind: YamlKind,
    val lineRange: IntRange,
    val scalarValue: String = "",
    val children: List<YamlNode> = emptyList(),
)

/** One `---`-separated document within a YAML stream — almost always there is only one. */
data class YamlDocument(
    val index: Int,
    val lineRange: IntRange,
    val root: List<YamlNode>,
)

/**
 * "Kind.name" derived from a document's top-level `kind:` + `metadata.name:`, falling back to a
 * positional name. Shared by YamlSkeletonBuilder (the multi-document index line) and
 * YamlStructuralAdapter (the same document's addressable name for tokenslayer_expand) so the
 * name shown in a skeleton is always exactly the name that resolves it.
 */
internal fun documentName(root: List<YamlNode>): String {
    val kind = root.firstOrNull { it.key == "kind" }?.scalarValue
    val name = root.firstOrNull { it.key == "metadata" }?.children?.firstOrNull { it.key == "name" }?.scalarValue
    return when {
        !kind.isNullOrBlank() && !name.isNullOrBlank() -> "$kind.$name"
        !name.isNullOrBlank() -> name
        !kind.isNullOrBlank() -> kind
        else -> "document"
    }
}
