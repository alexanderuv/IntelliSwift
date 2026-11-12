package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.output.BuildOutputInstantReaderImpl
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemOutputParserProvider
import com.intellij.openapi.util.SystemInfo
import com.intellij.task.ProjectTaskRunner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmPaths
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmProjectSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

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

    fun `test SwiftPM task diagnostics open the linked paths`() {
        if (SystemInfo.isWindows) return
        val temp = Files.createTempDirectory("swiftpm-task-paths").toRealPath()
        val real = Files.createDirectories(temp.resolve("real/App/Sources/App"))
        val linked = Files.createSymbolicLink(temp.resolve("App"), temp.resolve("real/App"))
        val settings = SwiftPmSettings.getInstance(project)
        settings.linkProject(SwiftPmProjectSettings(SwiftPmPaths.systemIndependent(linked)))
        try {
            val taskId = ExternalSystemTaskId.create(SwiftPm.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
            val parsers = ExternalSystemOutputParserProvider.EP_NAME.extensionList
                .filter { it.externalSystemId == SwiftPm.SYSTEM_ID }.flatMap { it.getBuildOutputParsers(taskId) }
            val events = Collections.synchronizedList(ArrayList<BuildEvent>())
            val buildId = Any()
            val reader = BuildOutputInstantReaderImpl(buildId, buildId, { _, event -> events += event }, parsers)
            // SwiftPM prints the physical path.
            reader.append("${real.resolve("A.swift")}:3:5: error: boom\n")
            reader.closeAndGetFuture().get(30, TimeUnit.SECONDS)
            val error = events.filterIsInstance<FileMessageEvent>().single()
            assertEquals(linked.resolve("Sources/App/A.swift"), error.filePosition.path)
        } finally {
            settings.unlinkExternalProject(SwiftPmPaths.systemIndependent(linked))
        }
    }

    fun `test task paths go to the package directory the project uses`() {
        if (SystemInfo.isWindows) return
        // ~/work links to the data volume: App is linked through it, Api by its physical path.
        val temp = Files.createTempDirectory("swiftpm-locations").toRealPath()
        val volume = Files.createDirectories(temp.resolve("data/work"))
        Files.createDirectories(volume.resolve("App"))
        Files.createDirectories(volume.resolve("Api"))
        val work = Files.createSymbolicLink(temp.resolve("work"), volume)
        val locations = SwiftPackageLocations(listOf(work.resolve("App"), volume.resolve("Api")))

        assertEquals(work.resolve("App/Sources/A.swift"), locations.map(volume.resolve("App/Sources/A.swift")))
        assertEquals(volume.resolve("Api/Sources/B.swift"), locations.map(volume.resolve("Api/Sources/B.swift")))
        // Under no package of the project: kept, though it is under the linked volume.
        assertEquals(volume.resolve("Other/C.swift"), locations.map(volume.resolve("Other/C.swift")))
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
