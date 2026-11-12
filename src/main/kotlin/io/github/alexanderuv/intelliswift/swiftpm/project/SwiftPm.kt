package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage

/** Identity of the SwiftPM External System integration (see ADR 0002). */
object SwiftPm {
    @JvmField
    val SYSTEM_ID: ProjectSystemId = ProjectSystemId("SWIFTPM", "SwiftPM")

    /** The tool window id; the External System looks it up by [ProjectSystemId.getReadableName]. */
    const val TOOL_WINDOW_ID: String = "SwiftPM"

    /** Files whose changes make a package need reloading. */
    val SETTINGS_FILE_NAMES: Set<String> = setOf(SwiftPackage.MANIFEST, SwiftPackage.RESOLVED)

    /** Directories SwiftPM writes into a package directory; they are excluded from content roots. */
    val EXCLUDED_DIRECTORY_NAMES: List<String> = listOf(SwiftPackage.BUILD_DIR, ".swiftpm")
}
