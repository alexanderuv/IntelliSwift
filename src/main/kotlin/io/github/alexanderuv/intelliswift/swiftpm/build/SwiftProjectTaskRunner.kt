package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ModuleFilesBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import org.jetbrains.concurrency.Promise

/**
 * Runs the IDE's build actions for Swift modules with `swift build`:
 * - *Build Project* and *Build Module* build each module's package (with its test targets), or
 *   only the module's target (`--target`) for a module of one target;
 * - *Rebuild* runs `swift package clean` first;
 * - *Compile* / *Recompile* of files builds the target the files belong to.
 *
 * It claims only Swift modules (see [SwiftPackageLocator.isSwiftModule]) and files inside a
 * SwiftPM package, so Java and other builds go to their own runners.
 */
class SwiftProjectTaskRunner : ProjectTaskRunner() {

    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean = when (projectTask) {
        is ModuleFilesBuildTask -> {
            val module = projectTask.module
            SwiftPackageLocator.isSwiftModule(module) ||
                !SwiftPackageLocator.isOwnedByAnotherBuildSystem(module) &&
                projectTask.files.isNotEmpty() &&
                projectTask.files.all(SwiftPackageLocator::isPackageFile)
        }
        is ModuleBuildTask -> SwiftPackageLocator.isSwiftModule(projectTask.module)
        else -> false
    }

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val requests = ReadAction.computeBlocking<List<SwiftBuildRequest>, RuntimeException> {
            withoutCoveredDependencies(project, tasks.flatMap { requestsFor(project, it) })
        }
        return SwiftBuildService.getInstance(project).build(requests)
    }

    /**
     * Drops builds of `path:` dependencies whose root package is built as a whole in the same run:
     * the root's `swift build` builds them already, into its own `.build`. It builds only their
     * library targets, though, so builds of a dependency's tests stay.
     */
    internal fun withoutCoveredDependencies(project: Project, requests: List<SwiftBuildRequest>): List<SwiftBuildRequest> {
        val wholeRoots = requests.filter { it.scope.target == null }.map { it.scope.packageDir.normalize() }.toSet()
        return requests.filterNot { request ->
            val covered = SwiftPackageLocator.buildingRoot(project, request.scope.packageDir)?.let { it in wholeRoots } == true
            covered && !request.buildTests && !SwiftPackageLocator.isTestTarget(project, request.scope)
        }
    }

    private fun requestsFor(project: Project, task: ProjectTask): List<SwiftBuildRequest> = when (task) {
        is ModuleFilesBuildTask -> {
            // Compiling files builds their targets; it never cleans, even though the task is not incremental.
            val scopes = task.files.mapNotNull { SwiftPackageLocator.scopeOf(project, it) }
                .ifEmpty { listOfNotNull(SwiftPackageLocator.scopeOf(task.module)) }
            scopes.map { SwiftBuildRequest(it) }
        }
        is ModuleBuildTask -> listOfNotNull(SwiftPackageLocator.scopeOf(task.module)).map {
            SwiftBuildRequest(it, buildTests = it.target == null && task.isIncludeTests, clean = !task.isIncrementalBuild)
        }
        else -> emptyList()
    }
}
