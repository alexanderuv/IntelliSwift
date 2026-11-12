package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.output.BuildOutputParser
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemOutputParserProvider

/**
 * Parses the output of SwiftPM External System tasks (such as `swift build` run from the SwiftPM
 * tool window), so their diagnostics are navigable in the Build tool window too.
 */
class SwiftBuildOutputParserProvider : ExternalSystemOutputParserProvider {
    override fun getExternalSystemId(): ProjectSystemId = SWIFTPM_SYSTEM_ID

    override fun getBuildOutputParsers(taskId: ExternalSystemTaskId): List<BuildOutputParser> = listOf(SwiftBuildOutputParser())

    companion object {
        /** The SwiftPM External System; the id must match the one the project import registers. */
        val SWIFTPM_SYSTEM_ID: ProjectSystemId = ProjectSystemId("SWIFTPM", "SwiftPM")
    }
}
