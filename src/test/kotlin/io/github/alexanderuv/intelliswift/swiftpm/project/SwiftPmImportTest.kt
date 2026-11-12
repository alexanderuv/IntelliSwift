package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.process.ProcessOutputType
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.externalSystem.autoimport.AutoImportProjectTracker
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.autoimport.ProjectAware
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmExecutionSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmProjectSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import com.intellij.openapi.util.SystemInfo
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Imports the fixture packages into a real IDE project: once by loading the `Greeter` package
 * with the local Swift toolchain through the External System, as linking does, and once by
 * importing the mapped model of a graph in which `Utils` is a checkout, which needs no Swift.
 * The tests that run Swift are skipped, with a message, when no toolchain is installed.
 */
class SwiftPmImportTest : HeavyPlatformTestCase() {

    // Loading a package starts processes, which must not happen on the UI thread.
    override fun runInDispatchThread(): Boolean = false

    // The project's modules are exactly the imported ones.
    override fun setUpModule() = Unit

    /** The fixture packages, copied to a temporary directory: `Greeter` linked, `Utils` its `path:` dependency. */
    private class Fixture(val greeter: Path, val utils: Path) {
        val greeterPath: String = SwiftPmPaths.systemIndependent(greeter)
    }

    /**
     * Links and loads `Greeter` through the External System; `null`, with a message, without
     * Swift. With [throughLink], the fixtures are reached through a symbolic link to their
     * directory, so the paths the IDE uses differ from the physical ones SwiftPM prints.
     */
    private fun linkAndLoadGreeter(test: String, throughLink: Boolean = false): Fixture? {
        val toolchain = SwiftToolchainSettings.toolchain()?.takeIf { it.isValid && it.version != null }
        if (toolchain == null) {
            println("SwiftPmImportTest: no Swift toolchain detected; skipping '$test'.")
            return null
        }
        val temp = createTempDirectory().toPath().toRealPath()
        val root = if (throughLink) Files.createSymbolicLink(temp.resolve("link"), Files.createDirectory(temp.resolve("real"))) else temp
        val fixture = Fixture(SwiftPmFixtures.copyPackages(root), root.resolve("Utils"))
        refresh(temp)
        TrustedProjects.setProjectTrusted(project, true)
        TrustedProjects.setProjectTrusted(fixture.greeter, true)
        link(fixture.greeterPath)
        return fixture
    }

    private fun link(packagePath: String) {
        SwiftPmSettings.getInstance(project).linkProject(SwiftPmProjectSettings(packagePath))
        refreshAndWait(packagePath)
    }

    private fun refreshAndWait(packagePath: String) {
        val done = CompletableFuture<Void>()
        ExternalSystemUtil.refreshProject(packagePath, ImportSpecBuilder(project, SwiftPm.SYSTEM_ID)
            .use(ProgressExecutionMode.MODAL_SYNC)
            .withCallback(done))
        done.get(5, TimeUnit.MINUTES)
    }

    fun `test a package reached through a symbolic link keeps the linked paths`() {
        if (SystemInfo.isWindows) return
        val fixture = linkAndLoadGreeter("symbolic link", throughLink = true) ?: return
        runReadActionBlocking {
            assertEquals(listOf(fixture.greeter), contentRoots(module("Greeter")))
            assertEquals(listOf(fixture.utils.resolve("Sources/Utils")), sourceRoots(module("Utils.Utils"), test = false))
            val source = file(fixture.greeter.resolve("Sources/Greeter/Greeter.swift"))
            assertEquals("Greeter", SwiftPmProjectModel.targetFor(project, source)?.name)
        }
        runBlocking { SwiftPmUnlinkedProjectAware().unlinkProject(project, fixture.greeterPath) }
        assertEquals(emptySet<String>(), moduleNames())
    }

    fun `test unlinking a root keeps the modules another linked root uses`() {
        val fixture = linkAndLoadGreeter("shared modules") ?: return
        // A second root that also depends on Utils; its import takes the Utils modules over.
        val second = fixture.utils.resolveSibling("Second")
        second.resolve("Sources/Second").createDirectories()
        second.resolve("Package.swift").writeText("""
            // swift-tools-version:6.0
            import PackageDescription

            let package = Package(
                name: "Second",
                dependencies: [.package(path: "../Utils")],
                targets: [.target(name: "Second", dependencies: [.product(name: "Utils", package: "Utils")])]
            )
        """.trimIndent())
        second.resolve("Sources/Second/Second.swift").writeText("import Utils\n")
        refresh(second)
        TrustedProjects.setProjectTrusted(second, true)
        val secondPath = SwiftPmPaths.systemIndependent(second)
        link(secondPath)

        runBlocking { SwiftPmUnlinkedProjectAware().unlinkProject(project, secondPath) }
        // The unlink reloads Greeter, which owns the Utils modules again; wait for a reload of it.
        refreshAndWait(fixture.greeterPath)
        assertFalse("Second" in moduleNames())
        assertTrue(moduleNames().containsAll(setOf("Utils", "Utils.Utils")))
        runReadActionBlocking {
            assertEquals(setOf("Greeter.CHelpers", "Utils.Utils"), moduleDependencies(module("Greeter.Greeter")))
            assertEquals("utils", SwiftPmProjectModel.packageOf(module("Utils.Utils"))?.identity)
        }
    }

    fun `test unlinking a root removes its modules that only share ids with another root's`() {
        val fixture = linkAndLoadGreeter("same identities") ?: return
        // Another root whose `path:` dependency is a different package also named Utils: same ids, other directory.
        val other = fixture.greeter.resolveSibling("other").createDirectories()
        SwiftPmFixtures.copyPackages(other)
        val otherGreeter = other.resolve("Greeter")
        refresh(other)
        TrustedProjects.setProjectTrusted(otherGreeter, true)
        val otherPath = SwiftPmPaths.systemIndependent(otherGreeter)
        link(otherPath)
        val otherUtils = SwiftPmPaths.systemIndependent(other.resolve("Utils"))
        fun modulesOf(dir: String) = ModuleManager.getInstance(project).modules.filter { ExternalSystemApiUtil.getExternalProjectPath(it) == dir }
        assertFalse("the other Utils has modules of its own", modulesOf(otherUtils).isEmpty())

        runBlocking { SwiftPmUnlinkedProjectAware().unlinkProject(project, otherPath) }

        assertEmpty(modulesOf(otherUtils).map { it.name })
        assertEmpty(modulesOf(otherPath).map { it.name })
        assertTrue(moduleNames().containsAll(setOf("Greeter", "Greeter.Greeter", "Utils", "Utils.Utils")))
        runReadActionBlocking {
            assertEquals(listOf(fixture.utils.resolve("Sources/Utils")), sourceRoots(module("Utils.Utils"), test = false))
        }
    }

    fun `test links and loads the Greeter package with SwiftPM`() {
        val fixture = linkAndLoadGreeter("links and loads") ?: return
        val (greeter, utils, greeterPath) = Triple(fixture.greeter, fixture.utils, fixture.greeterPath)

        assertEquals(
            setOf("Greeter", "Greeter.CHelpers", "Greeter.Greeter", "Greeter.greet", "Greeter.GreeterTests", "Greeter.Lint", "Utils", "Utils.Utils"),
            moduleNames(),
        )
        assertTrue(ModuleManager.getInstance(project).modules.all { ModuleType.get(it).id == SwiftPackageModuleType.ID })

        runReadActionBlocking {
            assertEquals(listOf(greeter), contentRoots(module("Greeter")))
            assertEquals(setOf(greeter.resolve(".build"), greeter.resolve(".swiftpm")), excluded(module("Greeter")))
            assertEquals(listOf(greeter.resolve("Sources/Greeter")), sourceRoots(module("Greeter.Greeter"), test = false))
            assertEquals(listOf(greeter.resolve("Tests/GreeterTests")), sourceRoots(module("Greeter.GreeterTests"), test = true))
            assertEquals(listOf(utils.resolve("Sources/Utils")), sourceRoots(module("Utils.Utils"), test = false))

            assertEquals(setOf("Greeter.CHelpers", "Utils.Utils"), moduleDependencies(module("Greeter.Greeter")))
            assertEquals(setOf("Greeter.Greeter"), moduleDependencies(module("Greeter.greet")))
            assertEquals(setOf("Greeter.Greeter"), moduleDependencies(module("Greeter.GreeterTests")))
            assertTrue(libraryDependencies(module("Greeter.Greeter")).isEmpty())

            // The project model other features use to map files back to SwiftPM.
            val source = file(greeter.resolve("Sources/Greeter/Greeter.swift"))
            assertEquals("Greeter", SwiftPmProjectModel.targetFor(project, source)?.name)
            assertEquals("greeter", SwiftPmProjectModel.packageFor(project, source)?.identity)
            assertEquals("greeter", SwiftPmProjectModel.packageFor(project, file(greeter.resolve("Package.swift")))?.identity)
            assertEquals("utils", SwiftPmProjectModel.packageFor(project, file(utils.resolve("Sources/Utils/Strings.swift")))?.identity)
            assertEquals(module("Greeter.greet"), SwiftPmProjectModel.moduleForTarget(project, greeterPath, "greet"))
        }

        // Auto-reload: the platform tracks the linked package, and the files it watches for it
        // are the manifests and Package.resolved of the root and of the editable Utils.
        val projectId = ExternalSystemProjectId(SwiftPm.SYSTEM_ID, greeterPath)
        assertTrue(projectId in AutoImportProjectTracker.getInstance(project).getActivatedProjects())
        val manager = SwiftPmManager()
        assertEquals(
            listOf(greeter.resolve("Package.swift"), greeter.resolve("Package.resolved"), utils.resolve("Package.swift"), utils.resolve("Package.resolved"))
                .map(SwiftPmPaths::systemIndependent).toSet(),
            ProjectAware(project, projectId, manager).settingsFiles,
        )
        assertEquals(greeterPath, manager.getAffectedExternalProjectPath(SwiftPmPaths.systemIndependent(utils.resolve("Package.swift")), project))
        assertNull(manager.getAffectedExternalProjectPath(SwiftPmPaths.systemIndependent(utils.resolve("Sources/Utils/Strings.swift")), project))
    }

    fun `test tasks run swift and stream its output`() {
        val fixture = linkAndLoadGreeter("tasks") ?: return
        val output = StringBuilder()
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
                synchronized(output) { output.append(text) }
            }
        }
        val id = ExternalSystemTaskId.create(SwiftPm.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
        val settings = SwiftPmExecutionSettings().apply { tasks = listOf("clean") }
        SwiftPmTaskManager().executeTasks(fixture.greeterPath, id, settings, listener)
        // The process handler reports the command line as system output.
        assertTrue(output.toString(), output.contains("--package-path ${fixture.greeter} clean"))

        settings.tasks = listOf("assemble")
        val error = runCatching { SwiftPmTaskManager().executeTasks(fixture.greeterPath, id, settings, listener) }.exceptionOrNull()
        assertTrue("$error", error is ExternalSystemException && "Unknown SwiftPM task 'assemble'" in error.message.orEmpty())
    }

    /** Links `Greeter` and imports its model with `Utils` as a checkout, without running Swift; returns `Greeter`. */
    private fun importWithCheckout(): Path {
        val root = createTempDirectory().toPath().toRealPath()
        val greeter = SwiftPmFixtures.copyPackages(root, utilsCheckout = true)
        refresh(root)
        SwiftPmSettings.getInstance(project).linkProject(SwiftPmProjectSettings(SwiftPmPaths.systemIndependent(greeter)))
        val model = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph(SwiftPmPaths.systemIndependent(root), utilsCheckout = true))
        ApplicationManager.getApplication().invokeAndWait { ProjectDataManager.getInstance().importData(model, project) }
        return greeter
    }

    fun `test checkout packages become libraries with their sources`() {
        val greeter = importWithCheckout()
        val checkout = greeter.resolve(".build/checkouts/Utils")

        assertEquals(setOf("Greeter", "Greeter.CHelpers", "Greeter.Greeter", "Greeter.greet", "Greeter.GreeterTests", "Greeter.Lint"), moduleNames())
        runReadActionBlocking {
            val library = libraryDependencies(module("Greeter.Greeter")).single().library!!
            assertEquals("SwiftPM: utils:Utils", library.name)
            assertEquals(listOf(file(checkout.resolve("Sources/Utils"))), library.getFiles(OrderRootType.SOURCES).toList())
            assertEquals(setOf("Greeter.CHelpers"), moduleDependencies(module("Greeter.Greeter")))

            val strings = file(checkout.resolve("Sources/Utils/Strings.swift"))
            val index = ProjectFileIndex.getInstance(project)
            assertTrue("checkout sources are library sources", index.isInLibrarySource(strings))
            assertFalse("checkouts are not project content", index.isInContent(strings))
            assertTrue(index.isInSourceContent(file(greeter.resolve("Sources/Greeter/Greeter.swift"))))
        }
    }

    fun `test linking state, opening and unlinking`() {
        val greeter = importWithCheckout()
        val greeterPath = SwiftPmPaths.systemIndependent(greeter)
        val unlinked = SwiftPmUnlinkedProjectAware()
        assertTrue(unlinked.isBuildFile(project, file(greeter.resolve("Package.swift"))))
        assertFalse(unlinked.isBuildFile(project, file(greeter.resolve("Sources/Greeter/Greeter.swift"))))
        assertTrue(unlinked.isLinkedProject(project, greeterPath))
        assertEquals(listOf(greeterPath), SwiftPmProjectModel.linkedPackagePaths(project))

        val opener = SwiftPmProjectOpenProcessor()
        assertTrue(opener.canOpenProject(file(greeter)))
        assertTrue(opener.canOpenProject(file(greeter.resolve("Package.swift"))))
        assertFalse(opener.canOpenProject(file(greeter.resolve("Sources"))))

        runBlocking { unlinked.unlinkProject(project, greeterPath) }
        assertFalse(unlinked.isLinkedProject(project, greeterPath))
        assertEquals(emptySet<String>(), moduleNames())
        assertTrue("the checkout library goes with its last user",
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries.isEmpty())
    }

    private fun refresh(root: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
    }

    private fun file(path: Path): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("no file $path")

    private fun moduleNames(): Set<String> = ModuleManager.getInstance(project).modules.map { it.name }.toSet()

    private fun module(name: String): Module = ModuleManager.getInstance(project).findModuleByName(name) ?: error("no module $name in ${moduleNames()}")

    private fun contentRoots(module: Module): List<Path> = ModuleRootManager.getInstance(module).contentRoots.map { it.toNioPath() }

    private fun excluded(module: Module): Set<Path> =
        ModuleRootManager.getInstance(module).contentEntries.flatMap { it.excludeFolderUrls }.map { Path.of(it.removePrefix("file://")) }.toSet()

    private fun sourceRoots(module: Module, test: Boolean): List<Path> =
        ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.toList() }
            .filter { it.isTestSource == test }
            .mapNotNull { it.file?.toNioPath() }

    private fun moduleDependencies(module: Module): Set<String> =
        ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<ModuleOrderEntry>().map { it.moduleName }.toSet()

    private fun libraryDependencies(module: Module): List<LibraryOrderEntry> =
        ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>()
}
