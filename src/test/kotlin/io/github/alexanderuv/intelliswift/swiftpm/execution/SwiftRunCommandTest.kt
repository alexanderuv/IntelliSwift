package io.github.alexanderuv.intelliswift.swiftpm.execution

import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** The `swift run` and `swift test` command lines of the Swift Run and Swift Test configurations. */
class SwiftRunCommandTest {
    private val swift = Path.of("/usr/bin/swift")
    private val commands = SwiftPackageCommands(SwiftToolchain(swift), Path.of("/pkg"))

    @Test fun `program arguments follow the product unchanged, without a separator`() {
        // swift run passes everything after the product to the program, a `--` included.
        assertEquals(
            listOf("run", "--package-path", "/pkg", "-c", "release", "greet", "--name", "-v", "Ada"),
            commands.run("greet", SwiftBuildConfiguration.RELEASE, listOf("--name", "-v", "Ada")).parametersList.list,
        )
        assertEquals(listOf("run", "--package-path", "/pkg", "-c", "debug"), commands.run(null).parametersList.list)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `program arguments need a product`() {
        commands.run(null, programArgs = listOf("Ada"))
    }

    @Test fun `test filters follow the extra arguments`() {
        assertEquals(
            listOf("test", "--package-path", "/pkg", "-c", "debug", "--event-stream-output-path", "/tmp/e", "--filter", "^M\\."),
            commands.test(listOf("^M\\."), extraArgs = listOf("--event-stream-output-path", "/tmp/e")).parametersList.list,
        )
    }
}
