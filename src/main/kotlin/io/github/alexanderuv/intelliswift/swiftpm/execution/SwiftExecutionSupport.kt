package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmPathMapping
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmToolchains
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import io.github.alexanderuv.intelliswift.toolchain.SwiftVersion
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Shared parts of the Swift Run and Swift Test configurations. */
object SwiftExecutionSupport {

    /** A toolchain and the version it runs in the package, which decides some command line flags. */
    class ResolvedToolchain(val toolchain: SwiftToolchain, val version: SwiftVersion?)

    /**
     * The configured or detected toolchain for the package in [packageDir], with the same checks as
     * syncs and builds (see [SwiftPmToolchains.require]). Its version is the one it reports in
     * [packageDir], where `swift run` and `swift test` run: a proxy such as swiftly picks the
     * toolchain by the directory. Detecting it and asking its version start processes, so on the
     * UI thread this runs under a modal progress.
     */
    fun toolchain(project: Project, packageDir: Path): ResolvedToolchain {
        val resolve = ThrowableComputable<ResolvedToolchain, ExecutionException> {
            val toolchain = try {
                SwiftPmToolchains.require(packageDir)
            } catch (e: ExternalSystemException) {
                throw ExecutionException(e.message, e)
            }
            ResolvedToolchain(toolchain, toolchain.versionIn(packageDir))
        }
        return if (ApplicationManager.getApplication().isDispatchThread) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(resolve, "Detecting Swift toolchain", true, project)
        } else {
            resolve.compute()
        }
    }

    /** Checks that [directory] is a SwiftPM package directory; returns it as a path. */
    fun checkPackageDirectory(directory: String?): Path {
        if (directory.isNullOrBlank()) throw RuntimeConfigurationError("The package directory is not set.")
        val path = try {
            Path.of(directory)
        } catch (_: InvalidPathException) {
            throw RuntimeConfigurationError("The package directory is not a valid path.")
        }
        if (!Files.isRegularFile(path.resolve(SwiftPackage.MANIFEST))) {
            throw RuntimeConfigurationError("$directory does not contain a ${SwiftPackage.MANIFEST}.")
        }
        return path
    }

    /**
     * Maps the paths `swift run` and `swift test` print for the package in [packageDirectory] to
     * the paths the IDE knows the files by. SwiftPM and the test frameworks print physical paths,
     * with symbolic links resolved; see [SwiftPmPathMapping]. Paths that are not valid are kept.
     */
    fun printedPathMapper(packageDirectory: String?): (String) -> String {
        val mapping = packageDirectory?.let { runCatching { SwiftPmPathMapping(Path.of(it)) }.getOrNull() } ?: return { it }
        return { path -> runCatching { mapping.map(Path.of(path)).toString() }.getOrDefault(path) }
    }

    /** Sets the environment of [command]: [envs] on top of the IDE's environment if [passParentEnvs]. */
    fun applyEnvironment(command: GeneralCommandLine, envs: Map<String, String>, passParentEnvs: Boolean): GeneralCommandLine =
        command.withEnvironment(envs).withParentEnvironmentType(
            if (passParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE,
        )
}

/**
 * Turns `path/File.swift:line[:column]` in console output (compiler diagnostics, test failures)
 * into links to the source.
 */
class SwiftFileLocationFilter(
    private val project: Project,
    /** Maps a printed path to the one the IDE knows the file by; see [SwiftExecutionSupport.printedPathMapper]. */
    private val mapPath: (String) -> String = { it },
) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val items = LOCATION.findAll(line).mapNotNull { m ->
            val file = LocalFileSystem.getInstance().findFileByPath(mapPath(m.groups[1]!!.value)) ?: return@mapNotNull null
            val lineNumber = m.groups[2]!!.value.toInt() - 1
            val column = (m.groups[3]?.value?.toIntOrNull() ?: 1) - 1
            val start = entireLength - line.length + m.range.first
            Filter.ResultItem(start, start + m.value.length, OpenFileHyperlinkInfo(project, file, lineNumber, column))
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private companion object {
        /** An absolute path (POSIX or Windows) to a `.swift` file, with a line and maybe a column. */
        val LOCATION = Regex("""((?:/|[A-Za-z]:[\\/])[^:\n]*?\.swift):(\d+)(?::(\d+))?""")
    }
}
