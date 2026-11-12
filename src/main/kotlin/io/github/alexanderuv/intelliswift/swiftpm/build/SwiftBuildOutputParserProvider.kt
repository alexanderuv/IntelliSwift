package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.output.BuildOutputParser
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemOutputParserProvider
import com.intellij.openapi.project.Project
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmProjectModel
import java.nio.file.Path

/**
 * Parses the output of SwiftPM External System tasks (such as `swift build` run from the SwiftPM
 * tool window), so their diagnostics are navigable in the Build tool window too.
 *
 * SwiftPM prints physical paths, and the task ID does not say which package a task ran for. So
 * the paths of diagnostics are mapped by [SwiftPackageLocations] to the packages of the task's
 * project; the output itself is left as it is, since it includes the user's own program's.
 */
class SwiftBuildOutputParserProvider : ExternalSystemOutputParserProvider {
    override fun getExternalSystemId(): ProjectSystemId = SwiftPm.SYSTEM_ID

    override fun getBuildOutputParsers(taskId: ExternalSystemTaskId): List<BuildOutputParser> {
        // Reading the imported data and resolving paths: done on first use, on the parser's thread.
        val locations = lazy { taskId.findProject()?.let(SwiftPackageLocations::of) ?: SwiftPackageLocations(emptyList()) }
        return listOf(SwiftBuildOutputParser { path -> locations.value.map(path) })
    }
}

/**
 * The project's paths for the physical paths SwiftPM prints: a path under the physical location
 * of one of the project's package directories (as the import stored them, so as the project's
 * modules and editors know them) is moved under that directory; the deepest one wins. Paths under
 * none are kept: unlike a mapping for one known package, this cannot tell which symbolic links a
 * path elsewhere would be reached through. If two package directories have the same physical
 * location (one directory linked twice, through different links), the first one is used.
 */
internal class SwiftPackageLocations(packageDirectories: List<Path>) {
    /** (physical, project) directory pairs, deepest physical first. */
    private val locations: List<Pair<Path, Path>> = packageDirectories
        .mapNotNull { dir -> runCatching { dir.toRealPath() to dir.toAbsolutePath().normalize() }.getOrNull() }
        .distinctBy { it.first }
        .sortedByDescending { it.first.nameCount }

    fun map(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val (physical, dir) = locations.firstOrNull { absolute.startsWith(it.first) } ?: return absolute
        return dir.resolve(physical.relativize(absolute))
    }

    companion object {
        /** The linked root packages of [project] and the editable (`path:`) packages of their graphs. */
        fun of(project: Project): SwiftPackageLocations = SwiftPackageLocations(
            SwiftPmProjectModel.linkedPackagePaths(project).flatMap { SwiftPmProjectModel.editablePackageDirectories(project, it) }.distinct(),
        )
    }
}
