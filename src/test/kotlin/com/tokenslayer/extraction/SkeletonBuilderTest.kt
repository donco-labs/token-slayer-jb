package com.tokenslayer.extraction

import com.tokenslayer.types.StructuralSymbol
import com.tokenslayer.types.SymbolKind
import com.tokenslayer.types.Verbosity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkeletonBuilderTest {
    private val builder = SkeletonBuilder()

    private fun makeClass(
        name: String,
        methods: List<String> = emptyList(),
    ): StructuralSymbol =
        StructuralSymbol(
            name = name,
            kind = SymbolKind.CLASS,
            kindLabel = "class",
            signatureLine = "public class $name",
            lineRange = 1..100,
            children =
                methods.map { method ->
                    StructuralSymbol(
                        name = method,
                        kind = SymbolKind.METHOD,
                        kindLabel = "method",
                        signatureLine = "public void $method()",
                        lineRange = 5..10,
                    )
                },
        )

    @Test fun `builds header with file and line info`() {
        val symbols = listOf(makeClass("Foo"))
        val result = builder.build(symbols, "/src/Foo.java", 100)
        assertTrue(result.startsWith("// Foo.java (100 lines →"))
    }

    @Test fun `includes class signature`() {
        val symbols = listOf(makeClass("MyService"))
        val result = builder.build(symbols, "MyService.java", 50)
        assertTrue("public class MyService" in result)
    }

    @Test fun `includes method signatures as children`() {
        val symbols = listOf(makeClass("Controller", listOf("doGet", "doPost")))
        val result = builder.build(symbols, "Controller.java", 80)
        assertTrue("doGet" in result)
        assertTrue("doPost" in result)
    }

    @Test fun `returns no-symbols message for empty input`() {
        val result = builder.build(emptyList(), "/src/Empty.java", 0)
        assertTrue("no symbols found" in result)
    }

    @Test fun `uses tree prefix for nested methods`() {
        val symbols = listOf(makeClass("Parser", listOf("parse")))
        val result = builder.build(symbols, "Parser.java", 30, Verbosity.STANDARD)
        assertTrue("├─" in result)
    }

    @Test fun `enum lists members in standard mode`() {
        val enumSymbol =
            StructuralSymbol(
                name = "Status",
                kind = SymbolKind.ENUM,
                kindLabel = "enum",
                signatureLine = "enum Status",
                lineRange = 1..10,
                children =
                    listOf(
                        StructuralSymbol("ACTIVE", SymbolKind.ENUM_MEMBER, "enum_member", "ACTIVE", 2..2),
                        StructuralSymbol("INACTIVE", SymbolKind.ENUM_MEMBER, "enum_member", "INACTIVE", 3..3),
                    ),
            )
        val result = builder.build(listOf(enumSymbol), "Status.java", 10, Verbosity.STANDARD)
        assertTrue("ACTIVE" in result)
        assertTrue("INACTIVE" in result)
    }

    @Test fun `minimal verbosity shows member count for enums`() {
        val enumSymbol =
            StructuralSymbol(
                name = "Direction",
                kind = SymbolKind.ENUM,
                kindLabel = "enum",
                signatureLine = "enum Direction",
                lineRange = 1..6,
                children =
                    listOf(
                        StructuralSymbol("NORTH", SymbolKind.ENUM_MEMBER, "enum_member", "NORTH", 2..2),
                        StructuralSymbol("SOUTH", SymbolKind.ENUM_MEMBER, "enum_member", "SOUTH", 3..3),
                    ),
            )
        val result = builder.build(listOf(enumSymbol), "Direction.java", 6, Verbosity.MINIMAL)
        assertTrue("2 members" in result)
        assertFalse("NORTH" in result)
    }

    @Test fun `skeleton is significantly shorter than a big class`() {
        val methods = (1..20).map { "method$it" }
        val symbols = listOf(makeClass("BigClass", methods))
        val skeleton = builder.build(symbols, "BigClass.java", 500)
        val skeletonLines = skeleton.lines().size
        assertTrue(skeletonLines < 30, "Expected skeleton < 30 lines but got $skeletonLines")
    }
}
