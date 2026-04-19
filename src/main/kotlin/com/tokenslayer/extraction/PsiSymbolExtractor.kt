package com.tokenslayer.extraction

import com.intellij.openapi.application.ReadAction
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
    /**
     * Extract top-level structural symbols from a PSI file.
     * Returns a list of top-level symbols with children nested inside.
     */
    fun extract(psiFile: PsiFile): List<StructuralSymbol> =
        ReadAction.compute<List<StructuralSymbol>, Throwable> {
            extractFromFile(psiFile)
        }

    private fun extractFromFile(psiFile: PsiFile): List<StructuralSymbol> {
        val language = psiFile.language.id.lowercase()
        return when {
            language.contains("java") -> extractJava(psiFile)
            language.contains("kotlin") -> extractKotlin(psiFile)
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
        sb.append(cls.name)
        cls.typeParameters.takeIf { it.isNotEmpty() }?.let {
            sb.append("<${it.joinToString(", ") { tp -> tp.name ?: "?" }}>")
        }
        // Safe: extract names via reference resolution to avoid PsiType API version issues
        cls.superClassType?.resolve()?.name?.let { sb.append(" extends $it") }
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

    // ── Kotlin Extraction ────────────────────────────────────────────────────

    private fun extractKotlin(file: PsiFile): List<StructuralSymbol> {
        // Use generic PSI children traversal for Kotlin —
        // KtClass etc. are in the Kotlin plugin, accessed by class name via reflection fallback
        return extractGeneric(file)
    }

    // ── Generic Extraction (fallback) ────────────────────────────────────────

    private fun extractGeneric(file: PsiFile): List<StructuralSymbol> {
        val result = mutableListOf<StructuralSymbol>()
        // Walk children looking for named elements (covers Kotlin, Python, JS, Go via PSI)
        file.children.forEach { child ->
            extractGenericElement(child)?.let { result.add(it) }
        }
        return result
    }

    private fun extractGenericElement(element: PsiElement): StructuralSymbol? {
        if (element is PsiWhiteSpace || element is PsiComment) return null

        val named = element as? PsiNamedElement ?: return null
        val name = named.name?.takeIf { it.isNotBlank() } ?: return null

        val kind = inferKind(element)
        val range = getRange(element)
        val signature = extractSignatureLine(element)

        val children =
            element.children.mapNotNull { child ->
                if (child is PsiNamedElement && child.name?.isNotBlank() == true) {
                    extractGenericElement(child)
                } else {
                    null
                }
            }

        return StructuralSymbol(
            name = name,
            kind = kind,
            kindLabel = kind.name.lowercase(),
            signatureLine = signature,
            lineRange = range,
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
        val doc =
            PsiDocumentManager.getInstance(element.project)
                .getDocument(element.containingFile) ?: return 0..0
        val startLine = doc.getLineNumber(element.textRange.startOffset)
        val endLine = doc.getLineNumber(element.textRange.endOffset)
        return startLine..endLine
    }
}
