package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemOutputParserProvider
import com.intellij.task.ProjectTaskRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration

/** The build integration's extensions and settings, as plugin.xml registers them. */
class SwiftBuildRegistrationTest : BasePlatformTestCase() {

    fun `test the task runner is registered`() {
        assertTrue(ProjectTaskRunner.EP_NAME.extensionList.any { it is SwiftProjectTaskRunner })
    }

    fun `test SwiftPM task output is parsed`() {
        val swiftPm = ProjectSystemId("SWIFTPM")
        val providers = ExternalSystemOutputParserProvider.EP_NAME.extensionList.filter { it.externalSystemId == swiftPm }
        val taskId = ExternalSystemTaskId.create(swiftPm, ExternalSystemTaskType.EXECUTE_TASK, project)
        assertTrue(providers.flatMap { it.getBuildOutputParsers(taskId) }.any { it is SwiftBuildOutputParser })
    }

    fun `test the build configuration defaults to debug and is edited on the settings page`() {
        val settings = SwiftBuildSettings.getInstance(project)
        assertEquals(SwiftBuildConfiguration.DEBUG, settings.configuration)

        val configurable = SwiftBuildConfigurable(project)
        try {
            assertNotNull(configurable.createComponent())
            assertFalse(configurable.isModified)
            settings.configuration = SwiftBuildConfiguration.RELEASE
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.configuration = SwiftBuildConfiguration.DEBUG
        }
    }
}
