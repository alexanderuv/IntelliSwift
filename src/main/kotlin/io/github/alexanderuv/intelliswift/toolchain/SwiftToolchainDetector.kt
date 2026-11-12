package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Finds installed Swift toolchains: the `swift` on `PATH`, the active Xcode toolchain
 * (`xcrun --find swift`), swiftly-managed toolchains, and the standard install locations of
 * swift.org toolchains on macOS, Linux and Windows. The first entry is the preferred one.
 */
object SwiftToolchainDetector {
    fun detect(): List<SwiftToolchain> {
        // Without Xcode or the Command Line Tools, /usr/bin/swift and xcrun are stubs that open
        // the system's "install developer tools" dialog when run, so neither is a toolchain.
        val appleShims = SystemInfo.isMac && !macDeveloperToolsInstalled()
        return candidates(useXcrun = SystemInfo.isMac && !appleShims)
            .filterNot { appleShims && it == MAC_SHIM }
            .map { SwiftToolchain(it) }.distinct().filter { it.isValid }.toList()
    }

    private fun candidates(useXcrun: Boolean): Sequence<Path> = sequence {
        val exe = if (SystemInfo.isWindows) "swift.exe" else "swift"
        val home = Path.of(System.getProperty("user.home"))

        yieldAll(inPath(exe))
        // swiftly installs a proxy `swift` in its bin directory.
        System.getenv("SWIFTLY_BIN_DIR")?.let { yield(Path.of(it, exe)) }
        yield(home.resolve(".swiftly/bin/$exe"))
        if (SystemInfo.isMac) {
            yield(home.resolve("Library/Application Support/swiftly/bin/swift"))
            if (useXcrun) xcrunSwift()?.let { yield(it) }
            for (dir in listOf(home.resolve("Library/Developer/Toolchains"), Path.of("/Library/Developer/Toolchains"))) {
                yieldAll(children(dir).filter { it.fileName.toString().endsWith(".xctoolchain") }.map { it.resolve("usr/bin/swift") })
            }
            yield(MAC_SHIM)
        } else if (SystemInfo.isWindows) {
            System.getenv("LOCALAPPDATA")?.let { local ->
                val toolchains = Path.of(local, "Programs", "Swift", "Toolchains")
                yieldAll(newestFirst(children(toolchains)).map { it.resolve("usr/bin/swift.exe") })
            }
        } else {
            yield(Path.of("/usr/bin/swift"))
            yield(Path.of("/usr/share/swift/usr/bin/swift"))
            yield(Path.of("/opt/swift/usr/bin/swift"))
        }
    }

    /**
     * [exe] in each `PATH` directory, in order. The `PATH` is the user's shell environment as the
     * platform loads it, which matters on macOS, where an IDE started from the Dock inherits a
     * minimal `PATH` from launchd.
     */
    private fun inPath(exe: String): List<Path> =
        EnvironmentUtil.getValue("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Path.of(it.trim(), exe) }.getOrNull() }
            .filter { Files.isRegularFile(it) }

    private fun children(dir: Path): List<Path> =
        if (dir.isDirectory()) runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList()) else emptyList()

    /**
     * Toolchain directories named by version (`6.1.0+Asserts`, `6.10.0+Asserts`), newest first.
     * The numeric components are compared as numbers, so 6.10 is newer than 6.9.
     */
    internal fun newestFirst(dirs: List<Path>): List<Path> {
        fun components(dir: Path): List<Int> =
            VERSION_PREFIX.find(dir.fileName.toString())?.value?.split('.')?.map { it.toIntOrNull() ?: Int.MAX_VALUE }.orEmpty()
        val byVersion = Comparator<Path> { a, b ->
            val (x, y) = components(a) to components(b)
            (0 until maxOf(x.size, y.size)).asSequence()
                .map { (x.getOrNull(it) ?: 0).compareTo(y.getOrNull(it) ?: 0) }
                .firstOrNull { it != 0 } ?: 0
        }
        return dirs.sortedWith(byVersion.thenBy { it.fileName.toString() }).reversed()
    }

    /** Whether Xcode or the Command Line Tools are installed; `xcode-select -p` never prompts. */
    private fun macDeveloperToolsInstalled(): Boolean {
        val xcodeSelect = Path.of("/usr/bin/xcode-select")
        if (!Files.isRegularFile(xcodeSelect)) return false
        val output = runCatching {
            CapturingProcessHandler(GeneralCommandLine(xcodeSelect.toString(), "-p")).runProcess(10_000)
        }.getOrNull() ?: return false
        if (output.isTimeout || output.exitCode != 0) return false
        return output.stdout.trim().takeIf { it.isNotEmpty() }?.let { Files.isDirectory(Path.of(it)) } == true
    }

    private fun xcrunSwift(): Path? {
        val xcrun = Path.of("/usr/bin/xcrun")
        if (!Files.isRegularFile(xcrun)) return null
        val output = runCatching {
            CapturingProcessHandler(GeneralCommandLine(xcrun.toString(), "--find", "swift")).runProcess(10_000)
        }.getOrNull() ?: return null
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    }

    /** macOS's `swift`, which runs the selected Xcode's or Command Line Tools' toolchain. */
    private val MAC_SHIM: Path = Path.of("/usr/bin/swift")

    private val VERSION_PREFIX = Regex("""^\d+(\.\d+)*""")
}
