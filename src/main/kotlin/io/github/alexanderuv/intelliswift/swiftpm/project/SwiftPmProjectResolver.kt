package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.importing.ProjectResolverPolicy
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandException
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraphLoader
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageJsonException
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmExecutionSettings

/**
 * Loads a linked package with SwiftPM (see [SwiftPackageGraphLoader]) and maps it with
 * [SwiftPmProjectMapper]. In preview mode, used while a project is not trusted, the manifest is
 * not run and only the package directory is mapped.
 */
class SwiftPmProjectResolver : ExternalSystemProjectResolver<SwiftPmExecutionSettings> {

    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: SwiftPmExecutionSettings?,
        resolverPolicy: ProjectResolverPolicy?,
        listener: ExternalSystemTaskNotificationListener,
    ): DataNode<ProjectData> {
        val root = SwiftPmPaths.packageDirectory(projectPath)
        if (isPreviewMode) return SwiftPmProjectMapper.preview(root)

        // Registered first, so that a cancel during the toolchain check is not lost.
        val cancelled = SwiftPmCancellation.register(id)
        try {
            val toolchain = SwiftPmToolchains.require()
            listener.onTaskOutput(id, "Loading package at $root with ${toolchain.swift} (Swift ${toolchain.version})\n", ProcessOutputType.SYSTEM)
            val graph = SwiftPackageGraphLoader(toolchain).load(root, cancelled::get) { text, outputType ->
                // Standard output is the JSON being parsed; progress and diagnostics go to stderr.
                if (!ProcessOutputType.isStdout(outputType)) listener.onTaskOutput(id, text, ProcessOutputType.fromKey(outputType))
            }
            return SwiftPmProjectMapper.map(graph)
        } catch (e: SwiftCommandException) {
            throw ExternalSystemException(e.message, e)
        } catch (e: SwiftPackageJsonException) {
            throw ExternalSystemException("Unexpected output from SwiftPM: ${e.message}", e)
        } catch (e: ExecutionException) {
            throw SwiftPmToolchains.cannotStart(e)
        } finally {
            SwiftPmCancellation.unregister(id)
        }
    }

    override fun cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean =
        SwiftPmCancellation.cancel(taskId)
}
