package io.github.alexanderuv.intelliswift.swiftpm.model

import java.nio.file.Path

/** A target's kind, as `swift package describe` reports it in `type`. */
enum class SwiftTargetKind(val json: String) {
    LIBRARY("library"),
    EXECUTABLE("executable"),
    TEST("test"),
    MACRO("macro"),
    PLUGIN("plugin"),
    SYSTEM_LIBRARY("system-target"),
    BINARY("binary"),
    SNIPPET("snippet"),
    UNKNOWN(""),
    ;

    val isTest: Boolean get() = this == TEST

    /** Whether the target has source files of its own that the IDE should treat as sources. */
    val hasSources: Boolean get() = this != BINARY && this != SYSTEM_LIBRARY

    companion object {
        fun of(json: String?): SwiftTargetKind = entries.firstOrNull { it.json == json } ?: UNKNOWN
    }
}

/** The language of a target's sources, from `module_type` (`SwiftTarget`, `ClangTarget`, ...). */
enum class SwiftModuleType(val json: String) {
    SWIFT("SwiftTarget"),
    CLANG("ClangTarget"),
    PLUGIN("PluginTarget"),
    SYSTEM_LIBRARY("SystemLibraryTarget"),
    BINARY("BinaryTarget"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(json: String?): SwiftModuleType = entries.firstOrNull { it.json == json } ?: UNKNOWN
    }
}

enum class SwiftProductKind {
    LIBRARY, EXECUTABLE, PLUGIN, MACRO, SNIPPET, TEST, UNKNOWN;

    companion object {
        fun of(json: String?): SwiftProductKind = when (json) {
            "library" -> LIBRARY
            "executable" -> EXECUTABLE
            "plugin" -> PLUGIN
            "macro" -> MACRO
            "snippet" -> SNIPPET
            "test" -> TEST
            else -> UNKNOWN
        }
    }
}

/** A dependency of a target on a product of another package: `.product(name:package:)`. */
data class SwiftProductDependency(val product: String, val packageName: String?)

data class SwiftTarget(
    val name: String,
    /** The module name (`c99name`), used in `import` and in test identifiers. */
    val moduleName: String,
    val kind: SwiftTargetKind,
    val moduleType: SwiftModuleType,
    /** The target's directory, absolute. */
    val path: Path,
    /** Source files, relative to [path]. */
    val sources: List<String>,
    /** Targets of the same package this target depends on. */
    val targetDependencies: List<String>,
    /** Products of other packages this target depends on. */
    val productDependencies: List<SwiftProductDependency>,
    /** Products of this package that contain this target. */
    val productMemberships: List<String>,
)

data class SwiftProduct(val name: String, val kind: SwiftProductKind, val targets: List<String>)

/** One package as `swift package describe` and `swift package dump-package` describe it. */
data class SwiftPackage(
    /** The package identity SwiftPM uses (the lowercased last path component of its location). */
    val identity: String,
    val name: String,
    /** The package directory, absolute. */
    val path: Path,
    val toolsVersion: String?,
    val products: List<SwiftProduct>,
    val targets: List<SwiftTarget>,
) {
    fun target(name: String): SwiftTarget? = targets.firstOrNull { it.name == name }
    fun product(name: String): SwiftProduct? = products.firstOrNull { it.name == name }

    val manifest: Path get() = path.resolve(MANIFEST)

    companion object {
        const val MANIFEST = "Package.swift"
        const val RESOLVED = "Package.resolved"
        const val BUILD_DIR = ".build"
    }
}

/** A node of `swift package show-dependencies --format json`. */
data class SwiftResolvedPackage(
    val identity: String,
    val name: String,
    val url: String,
    val version: String,
    val path: Path,
    val dependencies: List<SwiftResolvedPackage>,
) {
    /** This node and all nodes below it, each identity once, in depth-first order. */
    fun flatten(): List<SwiftResolvedPackage> {
        val seen = LinkedHashMap<String, SwiftResolvedPackage>()
        fun visit(node: SwiftResolvedPackage) {
            if (seen.putIfAbsent(node.identity, node) != null) return
            node.dependencies.forEach(::visit)
        }
        visit(this)
        return seen.values.toList()
    }
}
