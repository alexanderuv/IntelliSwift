package io.github.alexanderuv.intelliswift.swiftpm.model

import java.nio.file.Path

/**
 * A root package with its resolved dependencies: what the IDE turns into modules and libraries.
 */
class SwiftPackageGraph(
    val root: SwiftPackage,
    /** The dependency tree from `show-dependencies`; its root node is [root]. */
    val resolved: SwiftResolvedPackage,
    /** Every package of the graph, including [root], by identity. */
    val packages: Map<String, SwiftPackage>,
) {
    /** The dependency tree's nodes, each identity once, by identity. */
    private val nodes: Map<String, SwiftResolvedPackage> = resolved.flatten().associateBy { it.identity }

    /** All packages except [root], in dependency-tree order. */
    val dependencies: List<SwiftPackage> = nodes.values.drop(1).mapNotNull { packages[it.identity] }

    /**
     * Whether [pkg] is edited in place: the root, a `path:` dependency, or a package put in edit mode
     * with `swift package edit`. Packages SwiftPM checks out into `.build` are read-only.
     */
    fun isEditable(pkg: SwiftPackage): Boolean =
        pkg.identity == root.identity || !pkg.path.normalize().startsWith(root.path.resolve(SwiftPackage.BUILD_DIR).normalize())

    /** [pkg]'s node in the dependency tree: its resolved version, URL and dependencies. */
    fun resolvedNode(pkg: SwiftPackage): SwiftResolvedPackage? = nodes[pkg.identity]

    /** The packages [pkg] depends on directly. */
    fun directDependencies(pkg: SwiftPackage): List<SwiftPackage> =
        resolvedNode(pkg)?.dependencies.orEmpty().mapNotNull { packages[it.identity] }

    /**
     * The package and product a product dependency of a target of [from] refers to. A dependency
     * that names its package is matched by identity or name; one declared by name only is looked up
     * in [from]'s direct dependencies, as SwiftPM does.
     */
    fun resolveProduct(from: SwiftPackage, dependency: SwiftProductDependency): Pair<SwiftPackage, SwiftProduct>? {
        val candidates = directDependencies(from)
        val named = dependency.packageName?.let { name ->
            candidates.filter { it.identity.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
        }
        for (pkg in named ?: candidates) {
            pkg.product(dependency.product)?.let { return pkg to it }
        }
        return null
    }

    fun packageAt(path: Path): SwiftPackage? = packages.values.firstOrNull { it.path == path.normalize() }

    companion object {
        /**
         * Assembles the graph from SwiftPM output: the `show-dependencies` JSON for the root, and
         * [describeAndDump] giving `describe` and `dump-package` JSON for a package directory.
         */
        fun build(dependenciesJson: String, describeAndDump: (SwiftResolvedPackage) -> Pair<String, String?>): SwiftPackageGraph {
            val resolved = SwiftPackageJson.parseDependencies(dependenciesJson)
            val packages = LinkedHashMap<String, SwiftPackage>()
            for (node in resolved.flatten()) {
                val (describe, dump) = describeAndDump(node)
                packages[node.identity] = SwiftPackageJson.parsePackage(node.identity, describe, dump)
            }
            return SwiftPackageGraph(packages.getValue(resolved.identity), resolved, packages)
        }
    }
}
