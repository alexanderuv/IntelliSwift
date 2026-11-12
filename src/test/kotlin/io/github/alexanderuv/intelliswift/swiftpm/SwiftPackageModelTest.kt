package io.github.alexanderuv.intelliswift.swiftpm

import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftModuleType
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraph
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductDependency
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind
import io.github.alexanderuv.intelliswift.toolchain.SwiftVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Parses the SwiftPM output captured from the fixture packages in `src/test/testData/swiftpm`
 * (the `.json` files in `json/`, with the fixture directory replaced by `/FIXTURE`).
 */
class SwiftPackageModelTest {
    private fun json(name: String): String = Path.of("src/test/testData/swiftpm/json/$name").toFile().readText()

    private fun graph(): SwiftPackageGraph = SwiftPackageGraph.build(json("Greeter.dependencies.json")) { node ->
        val name = node.path.fileName.toString()
        json("$name.describe.json") to json("$name.dump.json")
    }

    @Test fun `targets keep their kind, language, directory and sources`() {
        val greeter = graph().root
        assertEquals("Greeter", greeter.name)
        assertEquals(Path.of("/FIXTURE/Greeter"), greeter.path)
        val byName = greeter.targets.associateBy { it.name }
        assertEquals(setOf("CHelpers", "Greeter", "greet", "GreeterTests", "Lint"), byName.keys)

        assertEquals(SwiftTargetKind.EXECUTABLE, byName.getValue("greet").kind)
        assertEquals(SwiftTargetKind.TEST, byName.getValue("GreeterTests").kind)
        assertEquals(SwiftTargetKind.PLUGIN, byName.getValue("Lint").kind)
        assertEquals(SwiftModuleType.CLANG, byName.getValue("CHelpers").moduleType)
        assertEquals(Path.of("/FIXTURE/Greeter/Plugins/Lint"), byName.getValue("Lint").path)
        assertEquals(listOf("GreeterTests.swift", "GreeterXCTests.swift"), byName.getValue("GreeterTests").sources)
    }

    @Test fun `dependencies name targets and the package of each product`() {
        val greeter = graph().root.target("Greeter")!!
        assertEquals(listOf("CHelpers"), greeter.targetDependencies)
        assertEquals(listOf(SwiftProductDependency("Utils", "Utils")), greeter.productDependencies)
    }

    @Test fun `products have kinds`() {
        val products = graph().root.products.associate { it.name to it.kind }
        assertEquals(mapOf("Greeter" to SwiftProductKind.LIBRARY, "greet" to SwiftProductKind.EXECUTABLE, "Lint" to SwiftProductKind.PLUGIN), products)
    }

    @Test fun `product dependencies resolve to the dependency package`() {
        val graph = graph()
        val (pkg, product) = graph.resolveProduct(graph.root, SwiftProductDependency("Utils", "Utils"))!!
        assertEquals("utils", pkg.identity)
        assertEquals(listOf("Utils"), product.targets)
        assertNull(graph.resolveProduct(graph.root, SwiftProductDependency("Missing", null)))
    }

    @Test fun `path dependencies are editable, checkouts are not`() {
        val graph = graph()
        assertEquals(listOf("utils"), graph.dependencies.map { it.identity })
        assertTrue(graph.isEditable(graph.root))
        assertTrue(graph.isEditable(graph.packages.getValue("utils")))
        val checkout = graph.packages.getValue("utils").copy(path = Path.of("/FIXTURE/Greeter/.build/checkouts/Utils"), identity = "remote")
        assertFalse(graph.isEditable(checkout))
    }

    @Test fun `warnings before the JSON are skipped, braces included`() {
        val graph = SwiftPackageGraph.build(json("Greeter.dependencies.json")) { node ->
            val name = node.path.fileName.toString()
            "warning: '$name': found 1 file(s) which are unhandled: /FIXTURE/{template}.txt\n" + json("$name.describe.json") to json("$name.dump.json")
        }
        assertEquals("Greeter", graph.root.name)
        assertEquals(listOf("utils"), graph.dependencies.map { it.identity })
    }

    @Test fun `swift versions parse from swift --version`() {
        assertEquals(SwiftVersion(6, 1, 2), SwiftVersion.parseVersionOutput("swift-driver version: 1.120.5 Apple Swift version 6.1.2 (swiftlang-6.1.2.1.2 clang-1700.0.13.5)"))
        val dev = SwiftVersion.parseVersionOutput("Swift version 6.2-dev (LLVM abc, Swift def)\nTarget: x86_64-unknown-linux-gnu")!!
        assertEquals("6.2-dev", dev.text)
        assertTrue(dev.atLeast(6, 2))
        assertNull(SwiftVersion.parseVersionOutput("bash: swift: command not found"))
    }
}
