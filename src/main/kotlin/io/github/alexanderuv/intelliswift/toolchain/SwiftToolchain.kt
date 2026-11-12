package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolute
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * A Swift toolchain, identified by its `swift` driver executable.
 *
 * The executable may be a proxy that picks the toolchain per directory: swiftly's `swift` follows
 * the `.swift-version` file of its working directory or the nearest one above it. So the version
 * a command runs with depends on the directory it runs in; see [versionIn].
 */
class SwiftToolchain(swift: Path) {
    val swift: Path = swift.absolute().normalize()

    /** The version `swift --version` reports outside any package, or `null` if it cannot be run. */
    val version: SwiftVersion? get() = versionIn(null)

    /**
     * The version `swift --version` reports when run in [directory], as commands for the package
     * there run, or `null` if it cannot be run.
     */
    fun versionIn(directory: Path?): SwiftVersion? = VersionCache.versionOf(swift, directory?.absolute()?.normalize())

    val isValid: Boolean get() = swift.isRegularFile() && (SystemInfo.isWindows || swift.isExecutable())

    /** A command line running this toolchain's `swift` with [args]. */
    fun command(vararg args: String): GeneralCommandLine =
        GeneralCommandLine(swift.toString(), *args)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is SwiftToolchain && other.swift == swift
    override fun hashCode(): Int = swift.hashCode()
    override fun toString(): String = "SwiftToolchain($swift)"

    /**
     * Avoids running `swift --version` for every use within one action (a sync, a test run).
     * Entries expire because the executable is often a proxy whose file never changes while the
     * toolchain behind it does: swiftly's `swift` after `swiftly use`, and the `/usr/bin/swift`
     * shim after `xcode-select -s`. Failures are not cached, so a toolchain that could not run
     * (say, before the Xcode license was accepted) is retried on the next use.
     */
    private object VersionCache {
        private class Entry(val modified: Long, val queriedAt: Long, val version: SwiftVersion)

        /** By executable and working directory (`null`: the user's home, outside any package). */
        private val entries = ConcurrentHashMap<Pair<Path, Path?>, Entry>()

        fun versionOf(swift: Path, directory: Path?): SwiftVersion? {
            val modified = runCatching { swift.getLastModifiedTime().toMillis() }.getOrNull() ?: return null
            val key = swift to directory
            val now = System.nanoTime()
            entries[key]?.takeIf { it.modified == modified && now - it.queriedAt < TTL_NANOS }?.let { return it.version }
            val version = query(swift, directory)
            if (version == null) entries.remove(key) else entries[key] = Entry(modified, now, version)
            return version
        }

        private fun query(swift: Path, directory: Path?): SwiftVersion? {
            if (!Files.isRegularFile(swift)) return null
            // Never the IDE's own working directory, which says nothing about the user's choice.
            val workingDirectory = directory?.takeIf { Files.isDirectory(it) } ?: Path.of(System.getProperty("user.home"))
            val output = runCatching {
                CapturingProcessHandler(GeneralCommandLine(swift.toString(), "--version")
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withWorkingDirectory(workingDirectory))
                    .runProcess(VERSION_TIMEOUT_MS)
            }.getOrNull() ?: return null
            if (output.isTimeout || output.exitCode != 0) return null
            return SwiftVersion.parseVersionOutput(output.stdout + "\n" + output.stderr)
        }

        private const val VERSION_TIMEOUT_MS = 15_000
        private val TTL_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}

/** A Swift compiler version, as in `Apple Swift version 6.1.2` or `Swift version 6.0-dev`. */
data class SwiftVersion(val major: Int, val minor: Int, val patch: Int = 0, val text: String = "$major.$minor.$patch") :
    Comparable<SwiftVersion> {

    override fun compareTo(other: SwiftVersion): Int =
        compareValuesBy(this, other, SwiftVersion::major, SwiftVersion::minor, SwiftVersion::patch)

    /** Equal when the numbers are, as [compareTo] says; [text] is only how the toolchain spelled it. */
    override fun equals(other: Any?): Boolean = other is SwiftVersion && compareTo(other) == 0

    override fun hashCode(): Int = (major * 31 + minor) * 31 + patch

    fun atLeast(major: Int, minor: Int = 0): Boolean = this >= SwiftVersion(major, minor)

    override fun toString(): String = text

    companion object {
        /** The oldest toolchain the SwiftPM integration supports. */
        val MINIMUM = SwiftVersion(6, 0)

        private val VERSION = Regex("""Swift version (\d+)\.(\d+)(?:\.(\d+))?(\S*)""")

        fun parseVersionOutput(output: String): SwiftVersion? {
            val m = VERSION.find(output) ?: return null
            val (major, minor, patch, suffix) = m.destructured
            val text = buildString {
                append(major).append('.').append(minor)
                if (patch.isNotEmpty()) append('.').append(patch)
                append(suffix)
            }
            return SwiftVersion(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0, text)
        }
    }
}
