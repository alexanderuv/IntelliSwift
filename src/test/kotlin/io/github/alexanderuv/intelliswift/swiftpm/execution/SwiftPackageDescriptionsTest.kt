package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** When [SwiftPackageDescriptions] runs SwiftPM; uses a stand-in `swift` that records its runs. */
class SwiftPackageDescriptionsTest : HeavyPlatformTestCase() {
    private lateinit var packageDir: Path
    private lateinit var runs: Path

    override fun setUp() {
        super.setUp()
        val root = createTempDirectoryWithSuffix("swift-describe").toRealPath()
        packageDir = root.resolve("Pkg").createDirectories()
        packageDir.resolve("Package.swift").writeText("// swift-tools-version:6.0\n")
        runs = root.resolve("runs.txt")
        val swift = root.resolve("bin/swift").also { it.parent.createDirectories() }
        swift.writeText(
            """
            #!/bin/sh
            if [ "$1" = "--version" ]; then echo "Swift version 6.1"; exit 0; fi
            echo "$*" >> '$runs'
            echo '{"name":"Pkg","path":"$packageDir","targets":[],"products":[]}'
            """.trimIndent() + "\n",
        )
        swift.toFile().setExecutable(true)
        val settings = SwiftToolchainSettings.getInstance()
        val configured = settings.swiftPath
        settings.swiftPath = swift.toString()
        Disposer.register(testRootDisposable) { settings.swiftPath = configured }
    }

    fun `test a package reached through a symbolic link has its targets at the linked paths`() {
        if (SystemInfo.isWindows) return
        // The stand-in prints physical paths, as SwiftPM does.
        packageDir.resolve("Sources/Lib").createDirectories().resolve("Lib.swift").writeText("")
        val swift = runs.resolveSibling("bin/swift")
        swift.writeText(
            """
            #!/bin/sh
            if [ "$1" = "--version" ]; then echo "Swift version 6.1"; exit 0; fi
            echo "$*" >> '$runs'
            echo '{"name":"Pkg","path":"$packageDir","targets":[{"name":"Lib","c99name":"Lib","type":"library","path":"Sources/Lib","sources":["Lib.swift"]}],"products":[]}'
            """.trimIndent() + "\n",
        )
        val linked = Files.createSymbolicLink(packageDir.resolveSibling("Linked"), packageDir)
        TrustedProjects.setProjectTrusted(project, true)
        TrustedProjects.setProjectTrusted(linked, true)
        val descriptions = SwiftPackageDescriptions.getInstance(project)

        val pkg = descriptions.load(linked)

        assertEquals(linked.resolve("Sources/Lib"), pkg.targets.single().path)
        val source = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(linked.resolve("Sources/Lib/Lib.swift"))!!
        val target = runReadActionBlocking { descriptions.targetOf(source) }
        assertEquals("Lib", target?.targetName)
        assertEquals(SwiftTargetKind.LIBRARY, target?.kind)

        val mapPath = SwiftExecutionSupport.printedPathMapper(linked.toString())
        assertEquals(linked.resolve("Tests/T.swift").toString(), mapPath(packageDir.resolve("Tests/T.swift").toString()))
        assertEquals("/elsewhere/X.swift", mapPath("/elsewhere/X.swift"))
    }

    fun `test an untrusted package is not described`() {
        if (SystemInfo.isWindows) return
        TrustedProjects.setProjectTrusted(project, false)
        TrustedProjects.setProjectTrusted(packageDir, false)
        val descriptions = SwiftPackageDescriptions.getInstance(project)

        val error = runCatching { descriptions.load(packageDir) }.exceptionOrNull()

        assertTrue("$error", error is SwiftPackageDescriptions.CannotDescribeException)
        assertFalse("swift ran: ${runs.takeIf(Files::exists)?.let(Files::readString)}", Files.exists(runs))

        // Trusting it lifts the restriction, as the failure was not remembered.
        TrustedProjects.setProjectTrusted(packageDir, true)
        assertEquals("Pkg", descriptions.load(packageDir).name)
        assertTrue(Files.readString(runs), "describe" in Files.readString(runs))
    }
}
