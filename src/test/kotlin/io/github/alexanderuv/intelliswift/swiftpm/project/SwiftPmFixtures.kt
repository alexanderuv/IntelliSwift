package io.github.alexanderuv.intelliswift.swiftpm.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraph
import java.nio.file.Path
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories

/**
 * The SwiftPM output captured from the fixture packages in `src/test/testData/swiftpm` (the
 * `.json` files in `json/`, recorded with the fixture directory replaced by `/FIXTURE`).
 */
object SwiftPmFixtures {
    val testData: Path = Path.of("src/test/testData/swiftpm").toAbsolutePath()

    private fun json(name: String): String = testData.resolve("json/$name").toFile().readText()

    /**
     * The Greeter graph with the fixtures placed at [fixtureRoot]. With [utilsCheckout], Utils is
     * not the `path:` dependency of the fixture but a checkout of a remote package under
     * `Greeter/.build/checkouts/Utils`, as SwiftPM lays out `url:` dependencies.
     */
    fun greeterGraph(fixtureRoot: String = "/FIXTURE", utilsCheckout: Boolean = false): SwiftPackageGraph {
        val utilsPath = if (utilsCheckout) "$fixtureRoot/Greeter/.build/checkouts/Utils" else "$fixtureRoot/Utils"
        fun relocate(text: String) = text.replace("/FIXTURE/Utils", utilsPath).replace("/FIXTURE", fixtureRoot)

        var dependencies = relocate(json("Greeter.dependencies.json"))
        if (utilsCheckout) {
            val tree = JsonParser.parseString(dependencies).asJsonObject
            tree.getAsJsonArray("dependencies")[0].asJsonObject.apply {
                addProperty("url", "https://example.com/utils.git")
                addProperty("version", "1.2.0")
            }
            dependencies = GsonBuilder().create().toJson(tree)
        }
        return SwiftPackageGraph.build(dependencies) { node ->
            val name = if (node.identity == "greeter") "Greeter" else "Utils"
            relocate(json("$name.describe.json")) to relocate(json("$name.dump.json"))
        }
    }

    /**
     * Copies the fixture packages into [root]: `Greeter` and `Utils` side by side or, with
     * [utilsCheckout], `Utils` under `Greeter/.build/checkouts`.
     */
    fun copyPackages(root: Path, utilsCheckout: Boolean = false): Path {
        val greeter = root.resolve("Greeter")
        copyPackage(testData.resolve("Greeter"), greeter)
        val utils = if (utilsCheckout) greeter.resolve(".build/checkouts/Utils") else root.resolve("Utils")
        utils.parent.createDirectories()
        copyPackage(testData.resolve("Utils"), utils)
        return greeter
    }

    /** Copies a package's sources, leaving out what a local `swift` run may have left in the fixture. */
    @OptIn(ExperimentalPathApi::class)
    private fun copyPackage(source: Path, target: Path) {
        source.copyToRecursively(target, followLinks = false) { from, to ->
            when {
                from.fileName.toString() in SwiftPm.EXCLUDED_DIRECTORY_NAMES || from.fileName.toString() == "Package.resolved" ->
                    CopyActionResult.SKIP_SUBTREE
                else -> from.copyToIgnoringExistingDirectory(to, followLinks = false)
            }
        }
    }
}
