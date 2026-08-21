package com.tokenslayer.yaml

import com.tokenslayer.extraction.SymbolExpander
import com.tokenslayer.types.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlStructuralAdapterTest {
    private val parser = YamlParser()
    private val expander = SymbolExpander()

    private fun symbolsFor(yaml: String) = YamlStructuralAdapter.toStructuralSymbols(parser.parse(yaml))

    @Test fun `a single document's top-level keys become top-level symbols`() {
        val symbols = symbolsFor("replicas: 3\nimage: nginx")
        assertEquals(listOf("replicas", "image"), symbols.map { it.name })
        assertEquals(SymbolKind.YAML_SCALAR, symbols[0].kind)
    }

    @Test fun `list items get positional names`() {
        val symbols = symbolsFor("items:\n  - a\n  - b")
        val items = symbols.single { it.name == "items" }.children
        assertEquals(listOf("0", "1"), items.map { it.name })
    }

    @Test fun `a top-level list document gets positional names too`() {
        val symbols = symbolsFor("- a\n- b\n- c")
        assertEquals(listOf("0", "1", "2"), symbols.map { it.name })
    }

    @Test fun `multi-document files get one top-level symbol per document, named Kind dot name`() {
        val yaml =
            """
            kind: Deployment
            metadata:
              name: api-server
            ---
            kind: Service
            metadata:
              name: api-server
            """.trimIndent()
        val symbols = symbolsFor(yaml)
        assertEquals(listOf("Deployment.api-server", "Service.api-server"), symbols.map { it.name })
    }

    @Test fun `duplicate Kind dot name across documents gets a disambiguating suffix`() {
        val yaml =
            """
            kind: ConfigMap
            metadata:
              name: shared
            ---
            kind: ConfigMap
            metadata:
              name: shared
            """.trimIndent()
        val symbols = symbolsFor(yaml)
        assertEquals(listOf("ConfigMap.shared", "ConfigMap.shared#2"), symbols.map { it.name })
    }

    // ── End-to-end through SymbolExpander ───────────────────────────────────
    // SymbolExpander is unmodified and untested for YAML specifically — these prove the
    // conversion genuinely produces a tree it can resolve a dotted path through, the same way
    // it already does for code.

    @Test fun `expand resolves a nested dotted path through a single-document file`() {
        val yaml =
            """
            spec:
              template:
                spec:
                  containers:
                    - name: api
                      image: myapp:1.4.2
            """.trimIndent()
        val symbols = symbolsFor(yaml)
        val outcome =
            expander.expand(
                symbols = symbols,
                content = yaml,
                filePath = "/deploy.yaml",
                query = "spec.template.spec.containers",
                fileTokens = 100,
                tokenCounter = { it.length },
            )
        val found = outcome as SymbolExpander.Outcome.Found
        assertTrue("name: api" in found.expanded.source)
        assertTrue("image: myapp:1.4.2" in found.expanded.source)
    }

    @Test fun `expand resolves one document out of a multi-document bundle by Kind dot name`() {
        val yaml =
            """
            kind: Deployment
            metadata:
              name: api-server
            spec:
              replicas: 3
            ---
            kind: Service
            metadata:
              name: api-server
            spec:
              port: 8080
            """.trimIndent()
        val symbols = symbolsFor(yaml)
        val outcome =
            expander.expand(
                symbols = symbols,
                content = yaml,
                filePath = "/bundle.yaml",
                query = "Service.api-server",
                fileTokens = 100,
                tokenCounter = { it.length },
            )
        val found = outcome as SymbolExpander.Outcome.Found
        assertTrue("port: 8080" in found.expanded.source)
        assertTrue("replicas: 3" !in found.expanded.source, "must not bleed into the sibling document")
    }

    @Test fun `expand reports an ambiguous bare name shared by two documents' children`() {
        val yaml =
            """
            kind: Deployment
            metadata:
              name: api-server
            spec:
              replicas: 3
            ---
            kind: Service
            metadata:
              name: api-server
            spec:
              replicas: 1
            """.trimIndent()
        val symbols = symbolsFor(yaml)
        val outcome =
            expander.expand(
                symbols = symbols,
                content = yaml,
                filePath = "/bundle.yaml",
                query = "replicas",
                fileTokens = 100,
                tokenCounter = { it.length },
            )
        assertTrue(outcome is SymbolExpander.Outcome.Ambiguous)
    }
}
