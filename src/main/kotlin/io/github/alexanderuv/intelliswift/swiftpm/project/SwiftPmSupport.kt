package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.ExecutionException
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
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
     * The toolchain to run SwiftPM with. Fails with a message the sync and run consoles show when
     * none is usable. Starts processes; call it off the UI thread.
     */
    fun require(): SwiftToolchain {
        val toolchain = SwiftToolchainSettings.toolchain()?.takeIf { it.isValid }
            ?: throw ExternalSystemException(
                "No Swift toolchain found. Install Swift, or configure the toolchain in Settings | Languages & Frameworks | Swift.",
            )
        val version = toolchain.version
            ?: throw ExternalSystemException(
                "Cannot run ${toolchain.swift}. Check the Swift toolchain in Settings | Languages & Frameworks | Swift.",
            )
        if (version < SwiftVersion.MINIMUM) {
            throw ExternalSystemException(
                "Swift $version is too old: SwiftPM projects need Swift ${SwiftVersion.MINIMUM} or later. " +
                    "Configure a newer toolchain in Settings | Languages & Frameworks | Swift.",
            )
        }
        return toolchain
    }

    /** The error for a `swift` that passed [require] but could not be started. */
    fun cannotStart(e: ExecutionException): ExternalSystemException = ExternalSystemException(
        "Cannot start swift: ${e.message}. Check the Swift toolchain in Settings | Languages & Frameworks | Swift.",
        e,
    )
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
