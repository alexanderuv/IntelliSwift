package io.github.alexanderuv.intelliswift.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Which detected toolchain is the default, and how version-named toolchain directories sort. */
class SwiftToolchainSelectionTest {
    private val old = SwiftToolchain(Path.of("/usr/bin/swift"))
    private val current = SwiftToolchain(Path.of("/home/me/.swiftly/bin/swift"))
    private val broken = SwiftToolchain(Path.of("/opt/broken/usr/bin/swift"))

    private val versions = mapOf(old to SwiftVersion(5, 10), current to SwiftVersion(6, 1))

    @Test fun `the first supported toolchain wins over an older one found earlier`() {
        assertEquals(current, SwiftToolchainSettings.preferredToolchain(listOf(old, broken, current), versions::get))
    }

    @Test fun `detection order decides between supported toolchains`() {
        val newer = SwiftToolchain(Path.of("/opt/swift/usr/bin/swift"))
        val all = versions + (newer to SwiftVersion(6, 2))
        assertEquals(current, SwiftToolchainSettings.preferredToolchain(listOf(current, newer), all::get))
    }

    @Test fun `without a supported toolchain the first one is kept, to report its version`() {
        assertEquals(old, SwiftToolchainSettings.preferredToolchain(listOf(old, broken), versions::get))
        assertNull(SwiftToolchainSettings.preferredToolchain(emptyList(), versions::get))
    }

    @Test fun `toolchain directories sort by version, newest first`() {
        val dirs = listOf("6.9.0+Asserts", "6.10.0+Asserts", "6.1.0+Asserts", "0.0.0+Asserts", "6.10.1+Asserts").map { Path.of("C:/Swift/Toolchains", it) }
        assertEquals(
            listOf("6.10.1+Asserts", "6.10.0+Asserts", "6.9.0+Asserts", "6.1.0+Asserts", "0.0.0+Asserts"),
            SwiftToolchainDetector.newestFirst(dirs).map { it.fileName.toString() },
        )
    }
}
