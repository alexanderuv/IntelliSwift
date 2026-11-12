package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandRunner
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmExecutionSettings

/**
 * Runs [SwiftPmTask]s for the package at the task's external project path, one after another,
 * stopping at the first that fails. The run configuration's arguments are passed to every
 * command (to the program for `run:<product>`), its environment to the process.
 *
 * Process output is reported through [ExternalSystemTaskNotificationListener.onTaskOutput]
 * unchanged, so the Run console shows it and output parsers registered for SwiftPM (the
 * `externalSystemOutputParserProvider` extension point) see every line.
 */
class SwiftPmTaskManager : ExternalSystemTaskManager<SwiftPmExecutionSettings> {

    override fun executeTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: SwiftPmExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
    ) {
        val tasks = settings.tasks.map { name ->
            SwiftPmTask.parse(name) ?: throw ExternalSystemException(
                "Unknown SwiftPM task '$name'. Tasks are ${SwiftPmTask.BUILD}, ${SwiftPmTask.BUILD}:<target>, ${SwiftPmTask.TEST}, " +
                    "${SwiftPmTask.CLEAN}, ${SwiftPmTask.RESOLVE}, ${SwiftPmTask.UPDATE} and ${SwiftPmTask.RUN}:<product>.",
            )
        }
        if (tasks.isEmpty()) throw ExternalSystemException("No SwiftPM task to run.")
        // Registered first, so that a cancel during the toolchain check is not lost.
        val cancelled = SwiftPmCancellation.register(id)
        try {
            val packageDir = SwiftPmPaths.packageDirectory(projectPath)
            val commands = SwiftPackageCommands(SwiftPmToolchains.require(packageDir), packageDir)
            for (task in tasks) {
                val command = task.commandLine(commands, settings.arguments).withEnvironmentOf(settings)
                // The process handler reports the command line itself, as system output.
                val output = SwiftCommandRunner.run(command, cancelled::get) { text, outputType ->
                    listener.onTaskOutput(id, text, ProcessOutputType.fromKey(outputType))
                }
                if (output.exitCode != 0) {
                    throw ExternalSystemException("Task '${task.name}' failed: `swift` exited with code ${output.exitCode}.")
                }
            }
        } catch (e: ExecutionException) {
            throw SwiftPmToolchains.cannotStart(e)
        } finally {
            SwiftPmCancellation.unregister(id)
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean =
        SwiftPmCancellation.cancel(id)

    private fun GeneralCommandLine.withEnvironmentOf(settings: SwiftPmExecutionSettings): GeneralCommandLine = apply {
        withEnvironment(settings.env)
        if (!settings.isPassParentEnvs) withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
    }
}
