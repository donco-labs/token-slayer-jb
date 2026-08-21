package com.tokenslayer.yaml

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlSkeletonBuilderTest {
    private val parser = YamlParser()
    private val builder = YamlSkeletonBuilder()

    private fun build(
        yaml: String,
        filePath: String = "values.yaml",
    ): String {
        val docs = parser.parse(yaml)
        return builder.build(docs, filePath, yaml.lines().size)
    }

    @Test fun `empty content reports no yaml found`() {
        assertTrue("no YAML content found" in build(""))
    }

    @Test fun `renders a flat map`() {
        val result = build("replicas: 3\nimage: nginx:1.25")
        assertTrue("replicas: 3" in result)
        assertTrue("image: nginx:1.25" in result)
    }

    @Test fun `renders nested maps with increasing indentation`() {
        val result = build("spec:\n  template:\n    replicas: 3")
        val lines = result.lines()
        val replicasLine = lines.first { "replicas: 3" in it }
        val templateLine = lines.first { it.trim() == "template:" }
        val leadingSpaces = { s: String -> s.length - s.trimStart().length }
        assertTrue(leadingSpaces(replicasLine) > leadingSpaces(templateLine))
    }

    @Test fun `collapses a homogeneous list of more than three map items`() {
        val yaml =
            (1..5).joinToString("\n", prefix = "items:\n") { i -> "  - name: item$i\n    value: v$i" }
        val result = build(yaml)
        assertTrue("name: item1" in result)
        assertTrue("(+ 4 more, same shape)" in result)
        assertFalse("item5" in result, "collapsed items beyond the first should not be rendered")
    }

    @Test fun `does not collapse a short list even if homogeneous`() {
        val yaml = "items:\n  - name: a\n  - name: b\n  - name: c"
        val result = build(yaml)
        assertTrue("name: a" in result)
        assertTrue("name: b" in result)
        assertTrue("name: c" in result)
        assertFalse("more, same shape" in result)
    }

    @Test fun `does not collapse a list of differently-shaped maps`() {
        val yaml = "items:\n  - name: a\n  - name: b\n    extra: x\n  - name: c\n    other: y"
        val result = build(yaml)
        assertTrue("name: a" in result)
        assertTrue("name: b" in result)
        assertTrue("name: c" in result)
        assertFalse("same shape" in result)
    }

    @Test fun `elides a very long scalar value`() {
        val long = "x".repeat(200)
        val result = build("cert: $long")
        assertFalse(long in result)
        assertTrue("(200 chars)" in result)
    }

    @Test fun `renders a block scalar as an elided placeholder`() {
        val yaml = "script: |\n  echo one\n  echo two\n  echo three\nnext: 1"
        val result = build(yaml)
        assertTrue("|…(3 lines)" in result)
        assertTrue("next: 1" in result)
    }

    @Test fun `truncates a long list of plain scalars`() {
        val yaml = (1..20).joinToString("\n", prefix = "tags:\n") { i -> "  - tag$i" }
        val result = build(yaml)
        assertTrue("tag1" in result)
        assertTrue("(+ 10 more)" in result)
        assertFalse("tag20" in result)
    }

    @Test fun `collapses nesting past the max depth to a bare count`() {
        var yaml = "value: leaf\n"
        repeat(8) { yaml = "level:\n  $yaml".replace("\n", "\n  ") }
        val result = build(yaml)
        assertTrue(result.contains("{…") || result.contains("keys}"), "expected a collapsed-depth marker: $result")
    }

    @Test fun `renders a multi-document index with one line per resource`() {
        val yaml =
            """
            apiVersion: v1
            kind: Deployment
            metadata:
              name: api-server
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: api-server
            """.trimIndent()
        val result = build(yaml, "bundle.yaml")
        assertTrue("[0] Deployment.api-server" in result)
        assertTrue("[1] Service.api-server" in result)
        assertTrue("2 documents" in result)
    }

    @Test fun `multi-document index falls back to a positional name without kind or metadata`() {
        val yaml = "a: 1\n---\nb: 2\n"
        val result = build(yaml)
        assertTrue("[0] document" in result)
        assertTrue("[1] document" in result)
    }

    @Test fun `a single top-level list document renders without crashing`() {
        val result = build("- a\n- b\n- c")
        assertTrue("- a" in result)
        assertTrue("- b" in result)
    }
}
