package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
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
    fun detect(): List<SwiftToolchain> =
        candidates().map { SwiftToolchain(it) }.distinct().filter { it.isValid }.toList()

    private fun candidates(): Sequence<Path> = sequence {
        val exe = if (SystemInfo.isWindows) "swift.exe" else "swift"
        val home = Path.of(System.getProperty("user.home"))

        PathEnvironmentVariableUtil.findInPath(exe)?.let { yield(it.toPath()) }
        // swiftly installs a proxy `swift` in its bin directory.
        System.getenv("SWIFTLY_BIN_DIR")?.let { yield(Path.of(it, exe)) }
        yield(home.resolve(".swiftly/bin/$exe"))
        if (SystemInfo.isMac) {
            yield(home.resolve("Library/Application Support/swiftly/bin/swift"))
            xcrunSwift()?.let { yield(it) }
            for (dir in listOf(home.resolve("Library/Developer/Toolchains"), Path.of("/Library/Developer/Toolchains"))) {
                yieldAll(children(dir).filter { it.fileName.toString().endsWith(".xctoolchain") }.map { it.resolve("usr/bin/swift") })
            }
            yield(Path.of("/usr/bin/swift"))
        } else if (SystemInfo.isWindows) {
            System.getenv("LOCALAPPDATA")?.let { local ->
                val toolchains = Path.of(local, "Programs", "Swift", "Toolchains")
                yieldAll(children(toolchains).sortedDescending().map { it.resolve("usr/bin/swift.exe") })
            }
        } else {
            yield(Path.of("/usr/bin/swift"))
            yield(Path.of("/usr/share/swift/usr/bin/swift"))
            yield(Path.of("/opt/swift/usr/bin/swift"))
        }
    }

    private fun children(dir: Path): List<Path> =
        if (dir.isDirectory()) runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList()) else emptyList()

    private fun xcrunSwift(): Path? {
        val xcrun = Path.of("/usr/bin/xcrun")
        if (!Files.isRegularFile(xcrun)) return null
        val output = runCatching {
            CapturingProcessHandler(GeneralCommandLine(xcrun.toString(), "--find", "swift")).runProcess(10_000)
        }.getOrNull() ?: return null
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    }
}
