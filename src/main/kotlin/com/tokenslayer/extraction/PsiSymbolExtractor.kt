package com.tokenslayer.extraction

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind

/**
 * Extracts structural symbols from a PsiFile using IntelliJ's PSI API.
 * This is the JetBrains equivalent of VS Code's SymbolExtractor (executeDocumentSymbolProvider).
 * PSI gives richer typed information than LSP — we get full modifiers, generics, and annotations.
 */
class PsiSymbolExtractor {
    companion object {
        /** Declaration kinds that own nested members and are therefore descended into. */
        private val CONTAINER_KINDS =
            setOf(
                SymbolKind.CLASS,
                SymbolKind.INTERFACE,
                SymbolKind.ENUM,
                SymbolKind.STRUCT,
                SymbolKind.TRAIT,
                SymbolKind.IMPL,
                SymbolKind.OBJECT,
                SymbolKind.MODULE,
                SymbolKind.NAMESPACE,
            )
    }

    /**
     * Extract top-level structural symbols from a PSI file.
     * Returns a list of top-level symbols with children nested inside.
     */
    fun extract(psiFile: PsiFile): List<StructuralSymbol> =
        com.intellij.openapi.application.ReadAction.compute<List<StructuralSymbol>, RuntimeException> {
            extractFromFile(psiFile)
        }

    private fun extractFromFile(psiFile: PsiFile): List<StructuralSymbol> {
        // Match the language id EXACTLY. A substring check (contains "java") is wrong:
        // JavaScript's language id is "JavaScript", so it would be routed to the Java PSI
        // extractor — which finds no PsiClass and returns an empty skeleton. That is why
        // JS/TS produced no savings. Only real Java uses the dedicated Java path; everything
        // else (Kotlin, Python, JS/TS, Go, Rust, …) uses the generic recursive traversal.
        return when (psiFile.language.id.lowercase()) {
            "java" -> extractJava(psiFile)
            else -> extractGeneric(psiFile)
        }
    }

    // ── Java Extraction ──────────────────────────────────────────────────────

    private fun extractJava(file: PsiFile): List<StructuralSymbol> {
        val result = mutableListOf<StructuralSymbol>()
        // Collect top-level classes (not nested — those come via children)
        PsiTreeUtil.getChildrenOfTypeAsList(file, PsiClass::class.java).forEach { cls ->
            result.add(convertJavaClass(cls))
        }
        return result
    }

    private fun convertJavaClass(cls: PsiClass): StructuralSymbol {
        val kind =
            when {
                cls.isInterface -> SymbolKind.INTERFACE
                cls.isEnum -> SymbolKind.ENUM
                cls.isAnnotationType -> SymbolKind.INTERFACE
                else -> SymbolKind.CLASS
            }

        val signature = buildJavaClassSignature(cls)
        val range = getRange(cls)
        val children = mutableListOf<StructuralSymbol>()

        // Fields (non-enum-constant)
        cls.fields.filterNot { it is PsiEnumConstant }.forEach { field ->
            children.add(
                StructuralSymbol(
                    name = field.name,
                    kind = SymbolKind.FIELD,
                    kindLabel = "field",
                    signatureLine = buildJavaFieldSignature(field),
                    lineRange = getRange(field),
                ),
            )
        }

        // Methods
        cls.methods.forEach { method ->
            children.add(
                StructuralSymbol(
                    name = method.name,
                    kind = if (method.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
                    kindLabel = if (method.isConstructor) "constructor" else "method",
                    signatureLine = buildJavaMethodSignature(method),
                    lineRange = getRange(method),
                ),
            )
        }

        // Nested classes
        cls.innerClasses.forEach { inner ->
            children.add(convertJavaClass(inner))
        }

        return StructuralSymbol(
            name = cls.name ?: "Anonymous",
            kind = kind,
            kindLabel = kind.name.lowercase(),
            signatureLine = signature,
            lineRange = range,
            children = children,
        )
    }

    private fun buildJavaClassSignature(cls: PsiClass): String {
        val sb = StringBuilder()
        val modifiers = cls.modifierList
        if (modifiers != null) {
            listOf("public", "protected", "private", "abstract", "final", "static").forEach { mod ->
                if (modifiers.hasModifierProperty(mod)) sb.append("$mod ")
            }
        }
        when {
            cls.isInterface -> sb.append("interface ")
            cls.isEnum -> sb.append("enum ")
            cls.isAnnotationType -> sb.append("@interface ")
            else -> sb.append("class ")
        }
        sb.append(cls.name ?: "Anonymous")
        cls.typeParameters.takeIf { it.isNotEmpty() }?.let {
            sb.append("<${it.joinToString(", ") { tp -> tp.name ?: "?" }}>")
        }
        // Use the stable PSI type API (extendsListTypes / implementsListTypes) rather than
        // the experimental JVM API (superClassType → JvmReferenceType). extendsListTypes also
        // omits the implicit java.lang.Object superclass, so we don't emit "extends Object".
        cls.extendsListTypes.firstOrNull()?.resolve()?.name?.let { sb.append(" extends $it") }
        val interfaces = cls.implementsListTypes.mapNotNull { it.resolve()?.name }
        if (interfaces.isNotEmpty()) sb.append(" implements ${interfaces.joinToString(", ")}")
        return sb.toString().trimEnd()
    }

    private fun buildJavaMethodSignature(method: PsiMethod): String {
        val sb = StringBuilder()
        val modifiers = method.modifierList
        listOf("public", "protected", "private", "abstract", "static", "final", "synchronized", "default").forEach { mod ->
            if (modifiers.hasModifierProperty(mod)) sb.append("$mod ")
        }
        method.returnType?.let { sb.append("${it.canonicalText} ") }
        sb.append(method.name)
        val params =
            method.parameterList.parameters.joinToString(", ") { p ->
                "${p.type.canonicalText} ${p.name}"
            }
        sb.append("($params)")
        return sb.toString().trimEnd()
    }

    private fun buildJavaFieldSignature(field: PsiField): String {
        val sb = StringBuilder()
        val modifiers = field.modifierList
        listOf("public", "protected", "private", "static", "final", "volatile", "transient").forEach { mod ->
            if (modifiers?.hasModifierProperty(mod) == true) sb.append("$mod ")
        }
        sb.append("${field.type.canonicalText} ${field.name}")
        return sb.toString().trimEnd()
    }

    // ── Generic Extraction (Kotlin, Python, JS/TS, Go, Rust, …) ──────────────
    // Kotlin (KtClass / KtNamedFunction / KtProperty), Python, JS/TS, Go and Rust are all
    // handled here: their declarations are PsiNamedElement and are classified structurally
    // by node-type name, so no per-language compile dependency is required.

    private fun extractGeneric(file: PsiFile): List<StructuralSymbol> = collectStructural(file)

    /**
     * Recursively collect structural declarations (classes, functions, methods,
     * fields, …) beneath [parent].
     *
     * The previous implementation only looked at a declaration's *direct* PSI
     * children, so members nested inside a class body — which in most languages
     * (Python, JS/TS, Kotlin, Go) live one or more levels down inside a statement
     * list / block / body node — were never found. That produced empty skeletons
     * and therefore no token savings for every non-Java language.
     *
     * This version transparently descends through container nodes that are not
     * themselves declarations (statement lists, blocks, `export`/`var` wrappers,
     * type-declaration wrappers) until it reaches the actual declarations, and it
     * collects the members of type-like declarations (classes, structs, traits, …).
     * It deliberately does NOT descend into function/method bodies — we only want
     * signatures, not local variables and closures.
     */
    private fun collectStructural(parent: PsiElement): List<StructuralSymbol> {
        val result = mutableListOf<StructuralSymbol>()
        for (child in parent.children) {
            if (child is PsiWhiteSpace || child is PsiComment) continue
            val symbol = buildStructural(child)
            if (symbol != null) {
                result.add(symbol)
            } else {
                // Not a declaration itself — descend through the container to find members.
                result.addAll(collectStructural(child))
            }
        }
        return result
    }

    private fun buildStructural(element: PsiElement): StructuralSymbol? {
        val named = element as? PsiNamedElement ?: return null
        val name = named.name?.takeIf { it.isNotBlank() } ?: return null
        val kind = inferKind(element)
        if (kind == SymbolKind.UNKNOWN) return null

        // Only type-like declarations own nested members. Functions/methods/fields are
        // leaves — we never walk into a function body (avoids local vars) or a field
        // initializer (avoids anonymous objects/lambdas leaking into the skeleton).
        val children = if (kind in CONTAINER_KINDS) collectStructural(element) else emptyList()

        return StructuralSymbol(
            name = name,
            kind = kind,
            kindLabel = kind.name.lowercase(),
            signatureLine = extractSignatureLine(element),
            lineRange = getRange(element),
            children = children,
        )
    }

    private fun inferKind(element: PsiElement): SymbolKind {
        val className = element.javaClass.simpleName.lowercase()
        return when {
            "class" in className -> SymbolKind.CLASS
            "interface" in className -> SymbolKind.INTERFACE
            "enum" in className -> SymbolKind.ENUM
            "method" in className || "fun" in className || "function" in className -> SymbolKind.METHOD
            "field" in className || "property" in className || "variable" in className -> SymbolKind.FIELD
            "constructor" in className -> SymbolKind.CONSTRUCTOR
            "struct" in className -> SymbolKind.STRUCT
            "trait" in className -> SymbolKind.TRAIT
            "object" in className -> SymbolKind.OBJECT
            else -> SymbolKind.UNKNOWN
        }
    }

    private fun extractSignatureLine(element: PsiElement): String {
        val text = element.text ?: return ""
        // Take first non-empty line as the signature
        return text.lines()
            .firstOrNull { it.trim().isNotEmpty() }
            ?.trim()
            ?.let { cleanSignature(it) }
            ?: ""
    }

    private fun cleanSignature(line: String): String =
        line.replace(Regex("""\s*\{.*$"""), "") // strip opening braces
            .replace(Regex("""\s*:\s*$"""), "")
            .trim()

    private fun getRange(element: PsiElement): IntRange {
        if (!element.isValid) return 0..0
        val containingFile = element.containingFile ?: return 0..0
        val doc =
            PsiDocumentManager.getInstance(element.project)
                .getDocument(containingFile) ?: return 0..0
        val range = element.textRange ?: return 0..0
        val startLine = doc.getLineNumber(range.startOffset)
        val endLine = doc.getLineNumber(range.endOffset)
        return startLine..endLine
    }
}
