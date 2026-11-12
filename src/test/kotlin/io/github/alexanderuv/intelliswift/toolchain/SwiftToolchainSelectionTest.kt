package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.openapi.util.SystemInfo
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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

    @Test fun `entered paths are normalized and invalid ones rejected`() {
        assertEquals("/usr/bin/swift", SwiftToolchainSettings.normalizePath("  /usr/bin/swift "))
        assertEquals("C:\\Swift\\swift.exe", SwiftToolchainSettings.normalizePath("\"C:\\Swift\\swift.exe\""))
        assertNull(SwiftToolchainSettings.normalizePath("   "))
        assertNull(SwiftToolchainSettings.normalizePath(null))
        assertEquals(Path.of("/usr/bin/swift"), SwiftToolchainSettings.toolchainAt("/usr/bin/swift")?.swift)
        assertNull("NUL is invalid in paths on every platform", SwiftToolchainSettings.toolchainAt("/usr/bin/sw\u0000ift"))
    }

    @Test fun `the version is the one swift reports in the package directory`() {
        assumeFalse(SystemInfo.isWindows)
        val dir = Files.createTempDirectory("swift-version")
        // A swiftly-like proxy: the toolchain follows the working directory's .swift-version.
        val swift = dir.resolve("swift").apply {
            writeText("#!/bin/sh\nif [ -f .swift-version ]; then echo \"Swift version $(cat .swift-version)\"; else echo 'Swift version 6.1'; fi\n")
            toFile().setExecutable(true)
        }
        val pinned = dir.resolve("pinned").createDirectories().also { it.resolve(".swift-version").writeText("5.10") }
        val toolchain = SwiftToolchain(swift)
        assertEquals(SwiftVersion(6, 1), toolchain.version)
        assertEquals(SwiftVersion(5, 10), toolchain.versionIn(pinned))
        assertEquals(SwiftVersion(6, 1), toolchain.versionIn(dir))
    }

    @Test fun `commands for a dependency run in the root package's directory`() {
        val toolchain = SwiftToolchain(Path.of("/usr/bin/swift"))
        val root = Path.of("/work/App")
        val dependency = root.resolve(".build/checkouts/Lib")
        val commands = SwiftPackageCommands(toolchain, dependency, workingDirectory = root)
        assertEquals(root.toFile(), commands.describe().workDirectory)
        assertTrue(dependency.toString() in commands.describe().parametersList.list)
        assertEquals(dependency.toFile(), SwiftPackageCommands(toolchain, dependency).dumpPackage().workDirectory)
    }

    @Test fun `toolchain directories sort by version, newest first`() {
        val dirs = listOf("6.9.0+Asserts", "6.10.0+Asserts", "6.1.0+Asserts", "0.0.0+Asserts", "6.10.1+Asserts").map { Path.of("C:/Swift/Toolchains", it) }
        assertEquals(
            listOf("6.10.1+Asserts", "6.10.0+Asserts", "6.9.0+Asserts", "6.1.0+Asserts", "0.0.0+Asserts"),
            SwiftToolchainDetector.newestFirst(dirs).map { it.fileName.toString() },
        )
    }
}
