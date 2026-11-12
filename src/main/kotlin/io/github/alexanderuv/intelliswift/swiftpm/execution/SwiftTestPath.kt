package io.github.alexanderuv.intelliswift.swiftpm.execution

/**
 * A test, or a group of tests, of a SwiftPM test target, in the form both the test tree and the
 * gutter use. [locationUrl] is the URL of the test tree node (and the key of its state in
 * `TestStateStorage`); [filter] is the `swift test --filter` regular expression that runs it.
 *
 * `swift test --filter` searches its regular expression in `Module.Class/method` for XCTest and in
 * the Swift Testing test ID (`Module.Suite/Inner/function(label:)/File.swift:line:column`) for
 * Swift Testing, so a group is anchored at its start and followed by `/` or the end.
 */
sealed interface SwiftTestPath {
    val locationUrl: String
    val filter: String

    /**
     * An XCTest case class, or one of its test methods. [module] is null when the output does not
     * name it (Linux XCTest prints `Class.method`) and it could not be determined otherwise.
     */
    data class XCTest(val module: String?, val className: String, val method: String? = null) : SwiftTestPath {
        override val locationUrl: String
            get() = "$XCTEST_PROTOCOL://${module.orEmpty()}/$className" + (method?.let { "/$it" } ?: "")

        override val filter: String
            get() {
                val prefix = if (module != null) "^${escapeRegex(module)}\\." else "(^|\\.)"
                return prefix + escapeRegex(className) + (method?.let { "/${escapeRegex(it)}$" } ?: "(/|$)")
            }

        fun classPath(): XCTest = copy(method = null)
    }

    /**
     * A Swift Testing suite or test function: [components] are the names after the module, as in
     * the test ID without its source location (`["Outer", "Inner", "deep()"]`).
     */
    data class SwiftTesting(val module: String, val components: List<String>) : SwiftTestPath {
        /** The test ID without the source location, e.g. `Module.Outer/Inner/deep()`. */
        val id: String get() = "$module." + components.joinToString("/")

        override val locationUrl: String get() = "$TESTING_PROTOCOL://$id"

        override val filter: String get() = "^" + escapeRegex(id) + "(/|$)"
    }

    /** All tests of a test target. */
    data class Target(val module: String) : SwiftTestPath {
        override val locationUrl: String get() = "$TARGET_PROTOCOL://$module"
        override val filter: String get() = "^" + escapeRegex(module) + "\\."
    }

    companion object {
        const val XCTEST_PROTOCOL = "swift-xctest"
        const val TESTING_PROTOCOL = "swift-testing"
        const val TARGET_PROTOCOL = "swift-target"

        /** A parameterized test case's URL is its function's URL with this suffix and the case. */
        const val CASE_SEPARATOR = "#case="

        /** Parses a test tree URL (protocol and path separately, as an `SMTestLocator` gets them). */
        fun parse(protocol: String, path: String): SwiftTestPath? = when (protocol) {
            XCTEST_PROTOCOL -> {
                val parts = path.split('/')
                when (parts.size) {
                    2 -> XCTest(parts[0].ifEmpty { null }, parts[1]).takeIf { it.className.isNotEmpty() }
                    3 -> XCTest(parts[0].ifEmpty { null }, parts[1], parts[2]).takeIf { it.className.isNotEmpty() && parts[2].isNotEmpty() }
                    else -> null
                }
            }
            TESTING_PROTOCOL -> parseTestingId(path.substringBefore(CASE_SEPARATOR))
            TARGET_PROTOCOL -> path.takeIf { it.isNotEmpty() && '/' !in it }?.let(::Target)
            else -> null
        }

        fun parse(url: String): SwiftTestPath? {
            val i = url.indexOf("://")
            return if (i < 0) null else parse(url.substring(0, i), url.substring(i + 3))
        }

        /** Parses a Swift Testing ID, with or without its trailing source location. */
        fun parseTestingId(id: String): SwiftTesting? {
            val withoutLocation = SOURCE_LOCATION_SUFFIX.replace(id, "")
            val dot = withoutLocation.indexOf('.')
            val slash = withoutLocation.indexOf('/')
            if (dot <= 0 || (slash in 0..<dot)) return null
            val module = withoutLocation.substring(0, dot)
            val components = withoutLocation.substring(dot + 1).split('/')
            if (components.any { it.isEmpty() }) return null
            return SwiftTesting(module, components)
        }

        /** The `/File.swift:line:column` that ends a Swift Testing function ID. */
        private val SOURCE_LOCATION_SUFFIX = Regex("""/[^/]+:\d+:\d+$""")

        /** Escapes [text] so that a `swift test --filter` regular expression matches it literally. */
        fun escapeRegex(text: String): String = buildString {
            for (c in text) {
                if (c in REGEX_SPECIAL) append('\\')
                append(c)
            }
        }

        private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"
    }
}
