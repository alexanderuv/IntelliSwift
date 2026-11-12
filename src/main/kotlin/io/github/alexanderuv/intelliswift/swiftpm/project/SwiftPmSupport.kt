package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.ExecutionException
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraph
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftResolvedPackage
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import io.github.alexanderuv.intelliswift.toolchain.SwiftVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object SwiftPmPaths {
    /** The package directory for a linked path, which may name the manifest itself. */
    fun packageDirectory(path: String): Path {
        val p = Path.of(path).toAbsolutePath().normalize()
        return if (p.fileName?.toString() == SwiftPackage.MANIFEST && !Files.isDirectory(p)) p.parent else p
    }

    /** The package directory [file] stands for: itself if it contains a manifest, its parent if it is one. */
    fun packageDirectory(file: VirtualFile): VirtualFile? = when {
        file.isDirectory -> file.takeIf { it.findChild(SwiftPackage.MANIFEST)?.isDirectory == false }
        file.name == SwiftPackage.MANIFEST -> file.parent
        else -> null
    }

    fun systemIndependent(path: Path): String = FileUtil.toSystemIndependentName(path.toAbsolutePath().normalize().toString())
}

internal object SwiftPmToolchains {
    /**
     * The toolchain to run SwiftPM with for the package in [packageDir]. Its version is checked as
     * `swift` reports it there, since a swiftly `swift` picks the toolchain per directory. Fails
     * with a message the sync, build and run consoles show when none is usable. Starts processes;
     * call it off the UI thread.
     */
    fun require(packageDir: Path): SwiftToolchain {
        val settings = SwiftToolchainSettings.getInstance()
        val configured = settings.swiftPath
        val toolchain = settings.toolchain()
            ?: throw ExternalSystemException(
                if (configured != null) "The configured Swift executable is not a valid path: $configured. $SETTINGS_HINT"
                else "No Swift toolchain found. Install Swift, or configure the toolchain in Settings | Languages & Frameworks | Swift.",
            )
        if (!toolchain.isValid) throw ExternalSystemException("${toolchain.swift} is not an executable. $SETTINGS_HINT")
        val version = toolchain.versionIn(packageDir)
            ?: throw ExternalSystemException("Cannot run ${toolchain.swift} in $packageDir. $SETTINGS_HINT")
        if (version < SwiftVersion.MINIMUM) {
            throw ExternalSystemException(
                "Swift $version is too old: SwiftPM projects need Swift ${SwiftVersion.MINIMUM} or later. " +
                    "Configure a newer toolchain in Settings | Languages & Frameworks | Swift.",
            )
        }
        return toolchain
    }

    private const val SETTINGS_HINT = "Check the Swift toolchain in Settings | Languages & Frameworks | Swift."

    /** The error for a `swift` that passed [require] but could not be started. */
    fun cannotStart(e: ExecutionException): ExternalSystemException = ExternalSystemException("Cannot start swift: ${e.message}. $SETTINGS_HINT", e)
}

/**
 * Maps the paths SwiftPM prints back to the paths the IDE knows the package by.
 *
 * SwiftPM prints physical paths: it handles `--package-path` by changing into the directory and
 * reading the working directory back, which resolves symbolic links. The IDE knows a package by
 * the path it was linked with, which may go through one (macOS's `/tmp` and `/var`, a workspace
 * linked into the home directory). Settings, the imported data and the files the editor opens all
 * use that path, so the model must too.
 *
 * A path under the physical location of the linked root, or of one of its ancestors, is moved
 * under that root or ancestor; the deepest one wins. That covers the root's own files, its
 * checkouts and `path:` dependencies next to it. Other paths are kept.
 */
internal class SwiftPmPathMapping(linkedRoot: Path) {
    /** (physical, linked) directory pairs from the root up, where the two differ; deepest first. */
    private val prefixes: List<Pair<Path, Path>> =
        generateSequence(linkedRoot.toAbsolutePath().normalize()) { it.parent }
            .mapNotNull { linked -> runCatching { linked.toRealPath() }.getOrNull()?.takeIf { it != linked }?.let { it to linked } }
            .toList()

    fun map(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val (physical, linked) = prefixes.firstOrNull { absolute.startsWith(it.first) } ?: return absolute
        return linked.resolve(physical.relativize(absolute))
    }

    fun map(graph: SwiftPackageGraph): SwiftPackageGraph {
        if (prefixes.isEmpty()) return graph
        val packages = graph.packages.mapValues { (_, pkg) -> map(pkg) }
        return SwiftPackageGraph(packages.getValue(graph.root.identity), map(graph.resolved), packages)
    }

    private fun map(pkg: SwiftPackage): SwiftPackage =
        pkg.copy(path = map(pkg.path), targets = pkg.targets.map { it.copy(path = map(it.path)) })

    private fun map(node: SwiftResolvedPackage): SwiftResolvedPackage =
        node.copy(path = map(node.path), dependencies = node.dependencies.map(::map))
}

/**
 * Cancellation flags of running syncs and tasks. The External System cancels through
 * `cancelTask` on the resolver or task manager while `resolveProjectInfo` or `executeTasks`
 * runs on another thread, and may use different instances for the two calls.
 */
internal object SwiftPmCancellation {
    private val flags = ConcurrentHashMap<ExternalSystemTaskId, AtomicBoolean>()

    fun register(id: ExternalSystemTaskId): AtomicBoolean = flags.computeIfAbsent(id) { AtomicBoolean(false) }

    fun unregister(id: ExternalSystemTaskId) {
        flags.remove(id)
    }

    /** Marks [id] cancelled; `false` if it is not running. */
    fun cancel(id: ExternalSystemTaskId): Boolean {
        val flag = flags[id] ?: return false
        flag.set(true)
        return true
    }
}
