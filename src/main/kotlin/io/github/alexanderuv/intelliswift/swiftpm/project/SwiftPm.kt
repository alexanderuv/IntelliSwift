package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import java.nio.file.Files
import java.nio.file.Path

/** Identity of the SwiftPM External System integration (see ADR 0002). */
object SwiftPm {
    @JvmField
    val SYSTEM_ID: ProjectSystemId = ProjectSystemId("SWIFTPM", "SwiftPM")

    /** The tool window id; the External System looks it up by [ProjectSystemId.getReadableName]. */
    const val TOOL_WINDOW_ID: String = "SwiftPM"

    /** Files whose changes make a package need reloading; see [isSettingsFile] for the manifest variants. */
    val SETTINGS_FILE_NAMES: Set<String> = setOf(SwiftPackage.MANIFEST, SwiftPackage.RESOLVED)

    /** A version-specific manifest, which SwiftPM evaluates instead of `Package.swift` for matching toolchains. */
    private val VERSIONED_MANIFEST = Regex("""Package@swift-\d+(\.\d+){0,2}\.swift""")

    /** Whether a change of the file named [fileName] in a package directory makes the package need reloading. */
    fun isSettingsFile(fileName: String): Boolean = fileName in SETTINGS_FILE_NAMES || VERSIONED_MANIFEST.matches(fileName)

    /** The settings files of the package in [directory]: the fixed ones and the version-specific manifests it has. */
    fun settingsFiles(directory: Path): List<Path> {
        val versioned = runCatching {
            Files.list(directory).use { files -> files.filter { isSettingsFile(it.fileName.toString()) }.toList() }
        }.getOrDefault(emptyList())
        return (SETTINGS_FILE_NAMES.map(directory::resolve) + versioned).distinct()
    }

    /** Directories SwiftPM writes into a package directory; they are excluded from content roots. */
    val EXCLUDED_DIRECTORY_NAMES: List<String> = listOf(SwiftPackage.BUILD_DIR, ".swiftpm")
}
