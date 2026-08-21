package com.tokenslayer.yaml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YamlParserTest {
    private val parser = YamlParser()

    private fun parseOneDoc(content: String): List<YamlNode> {
        val docs = parser.parse(content)
        assertEquals(1, docs.size, "expected exactly one document")
        return docs.single().root
    }

    @Test fun `parses a flat map`() {
        val root = parseOneDoc("a: 1\nb: 2")
        assertEquals(listOf("a", "b"), root.map { it.key })
        assertEquals(YamlKind.SCALAR, root[0].kind)
        assertEquals("1", root[0].scalarValue)
        assertEquals("2", root[1].scalarValue)
    }

    @Test fun `parses a nested map`() {
        val root = parseOneDoc("a:\n  b: 1\n  c: 2")
        assertEquals(1, root.size)
        assertEquals("a", root[0].key)
        assertEquals(YamlKind.MAP, root[0].kind)
        assertEquals(listOf("b", "c"), root[0].children.map { it.key })
    }

    @Test fun `parses a list of scalars`() {
        val root = parseOneDoc("items:\n  - x\n  - y")
        assertEquals(YamlKind.LIST, root[0].kind)
        assertEquals(2, root[0].children.size)
        assertTrue(root[0].children.all { it.key == null })
        assertEquals(listOf("x", "y"), root[0].children.map { it.scalarValue })
    }

    @Test fun `parses a list of maps with multiple keys each`() {
        val yaml =
            """
            containers:
              - name: api
                image: myapp:1.4.2
              - name: db
                image: postgres:16
            """.trimIndent()
        val root = parseOneDoc(yaml)
        val containers = root[0]
        assertEquals(YamlKind.LIST, containers.kind)
        assertEquals(2, containers.children.size)
        val first = containers.children[0]
        assertEquals(YamlKind.MAP, first.kind)
        assertEquals(listOf("name", "image"), first.children.map { it.key })
        assertEquals("api", first.children[0].scalarValue)
        assertEquals("myapp:1.4.2", first.children[1].scalarValue)
        assertEquals("db", containers.children[1].children[0].scalarValue)
    }

    @Test fun `parses a list item whose value is a nested block after a bare dash`() {
        val yaml =
            """
            items:
              -
                name: x
                val: y
            """.trimIndent()
        val root = parseOneDoc(yaml)
        val item = root[0].children.single()
        assertEquals(YamlKind.MAP, item.kind)
        assertEquals(listOf("name", "val"), item.children.map { it.key })
    }

    @Test fun `splits a multi-document stream`() {
        val yaml = "---\nkind: A\n---\nkind: B\n"
        val docs = parser.parse(yaml)
        assertEquals(2, docs.size)
        assertEquals("A", docs[0].root.single { it.key == "kind" }.scalarValue)
        assertEquals("B", docs[1].root.single { it.key == "kind" }.scalarValue)
    }

    @Test fun `a single document with no leading dashes is still one document`() {
        val docs = parser.parse("a: 1")
        assertEquals(1, docs.size)
        assertEquals(0, docs[0].index)
    }

    @Test fun `consumes a block scalar body without losing the next sibling`() {
        val yaml =
            """
            script: |
              echo hi
              echo bye
            next: 1
            """.trimIndent()
        val root = parseOneDoc(yaml)
        assertEquals(listOf("script", "next"), root.map { it.key })
        assertEquals(YamlKind.SCALAR, root[0].kind)
        assertEquals("1", root[1].scalarValue)
    }

    @Test fun `tolerates blank lines and comments`() {
        val yaml =
            """
            # a comment
            a: 1

            # another
            b: 2
            """.trimIndent()
        val root = parseOneDoc(yaml)
        assertEquals(listOf("a", "b"), root.map { it.key })
    }

    @Test fun `a colon with no following space is not treated as a key separator`() {
        val root = parseOneDoc("""url: "http://host:8080/path"""")
        assertEquals("url", root[0].key)
        assertEquals(""""http://host:8080/path"""", root[0].scalarValue)
    }

    @Test fun `a flow collection is kept as opaque scalar text`() {
        val root = parseOneDoc("tags: [a, b, c]")
        assertEquals(YamlKind.SCALAR, root[0].kind)
        assertEquals("[a, b, c]", root[0].scalarValue)
    }

    @Test fun `parses a realistic kubernetes-shaped document end to end`() {
        val yaml =
            """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: api-server
              namespace: prod
            spec:
              replicas: 3
              template:
                spec:
                  containers:
                    - name: api
                      image: myapp:1.4.2
                      ports:
                        - containerPort: 8080
            """.trimIndent()
        val root = parseOneDoc(yaml)
        assertEquals("Deployment", root.single { it.key == "kind" }.scalarValue)
        val metadata = root.single { it.key == "metadata" }
        assertEquals("api-server", metadata.children.single { it.key == "name" }.scalarValue)
        val containers =
            root.single { it.key == "spec" }
                .children.single { it.key == "template" }
                .children.single { it.key == "spec" }
                .children.single { it.key == "containers" }
        assertEquals(YamlKind.LIST, containers.kind)
        val container = containers.children.single()
        assertEquals("api", container.children.single { it.key == "name" }.scalarValue)
        val ports = container.children.single { it.key == "ports" }
        assertEquals(YamlKind.LIST, ports.kind)
        assertEquals("8080", ports.children.single().children.single { it.key == "containerPort" }.scalarValue)
    }

    @Test fun `empty content produces no documents`() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("\n\n").isEmpty())
    }

    @Test fun `a lone document separator produces no documents`() {
        assertTrue(parser.parse("---\n---\n").isEmpty())
    }

    @Test fun `line ranges are 0-based and cover a nested value`() {
        val docs = parser.parse("a:\n  b: 1\n  c: 2\nd: 3")
        val root = docs.single().root
        assertEquals(0, root[0].lineRange.first) // "a:" is line 0
        assertEquals(2, root[0].lineRange.last) // last child "c: 2" is line 2
        assertEquals(3, root[1].lineRange.first) // "d: 3" is line 3
    }
}
