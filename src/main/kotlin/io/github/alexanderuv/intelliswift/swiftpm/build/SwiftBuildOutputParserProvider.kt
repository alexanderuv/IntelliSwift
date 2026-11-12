package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.output.BuildOutputParser
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemOutputParserProvider
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm

/**
 * Parses the output of SwiftPM External System tasks (such as `swift build` run from the SwiftPM
 * tool window), so their diagnostics are navigable in the Build tool window too. The task
 * manager already reports the output with the project's paths (see `SwiftPmTaskManager`), so the
 * parser maps none; the task ID would not tell which package's paths to map anyway.
 */
class SwiftBuildOutputParserProvider : ExternalSystemOutputParserProvider {
    override fun getExternalSystemId(): ProjectSystemId = SwiftPm.SYSTEM_ID

    override fun getBuildOutputParsers(taskId: ExternalSystemTaskId): List<BuildOutputParser> = listOf(SwiftBuildOutputParser())
}
