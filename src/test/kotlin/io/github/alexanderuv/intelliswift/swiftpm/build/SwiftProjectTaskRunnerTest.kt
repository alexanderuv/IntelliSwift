package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.FinishEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.StartEvent
import com.intellij.build.events.SuccessResult
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.task.EmptyCompileScopeBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskManager
import com.intellij.task.impl.ModuleBuildTaskImpl
import com.intellij.task.impl.ModuleFilesBuildTaskImpl
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText

class SwiftProjectTaskRunnerTest : HeavyPlatformTestCase() {
    private val runner = SwiftProjectTaskRunner()
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = createTempDirectoryWithSuffix("swift-build").toRealPath()
        for (file in listOf(
            "Pkg/Package.swift", "Pkg/Sources/Lib/Lib.swift", "Pkg/Tests/LibTests/LibTests.swift", "Pkg/Plugins/Lint/Lint.swift",
            "Outer/Inner/Package.swift", "Outer/Inner/Sources/Inner/Inner.swift", "Outer/Main.java", "plain/Main.java",
        )) {
            root.resolve(file).also { it.parent.createDirectories() }.writeText("")
        }
    }

    private fun vfs(path: Path): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!

    private fun module(name: String, typeId: String, contentRoot: String): Module {
        val dir = root.resolve(contentRoot)
        val module = WriteAction.computeAndWait<Module, RuntimeException> {
            ModuleManager.getInstance(project).newModule(root.resolve("$name.iml"), typeId)
        }
        PsiTestUtil.addContentRoot(module, vfs(dir))
        return module
    }

    private fun canRun(task: ProjectTask) = runner.canRun(project, task, null)

    // region canRun

    fun `test claims modules of the Swift module type`() {
        assertTrue(canRun(ModuleBuildTaskImpl(module("swift", SwiftPackageLocator.SWIFT_MODULE_TYPE_ID, "plain"))))
    }

    fun `test claims modules inside a SwiftPM package`() {
        assertTrue(canRun(ModuleBuildTaskImpl(module("pkg", JAVA, "Pkg"))))
        assertTrue(canRun(ModuleBuildTaskImpl(module("lib", JAVA, "Pkg/Sources/Lib"))))
    }

    fun `test leaves other modules alone`() {
        assertFalse(canRun(ModuleBuildTaskImpl(module("plain", JAVA, "plain"))))
        // A package below the content root does not make the module a Swift one.
        assertFalse(canRun(ModuleBuildTaskImpl(module("outer", JAVA, "Outer"))))
    }

    fun `test leaves modules of other build systems alone`() {
        val gradle = module("gradle", JAVA, "Pkg")
        WriteAction.runAndWait<RuntimeException> {
            ExternalSystemModulePropertyManager.getInstance(gradle).setExternalId(ProjectSystemId("GRADLE"))
        }
        assertFalse(canRun(ModuleBuildTaskImpl(gradle)))
    }

    fun `test claims files inside a SwiftPM package`() {
        val outer = module("outer", JAVA, "Outer")
        assertTrue(canRun(ModuleFilesBuildTaskImpl(outer, false, vfs(root.resolve("Outer/Inner/Sources/Inner/Inner.swift")))))
        assertFalse(canRun(ModuleFilesBuildTaskImpl(outer, false, vfs(root.resolve("Outer/Main.java")))))
    }

    fun `test leaves other tasks alone`() {
        val emptyScope = object : EmptyCompileScopeBuildTask {
            override fun isIncrementalBuild() = true
            override fun getPresentableName() = "Empty scope"
        }
        assertFalse(canRun(emptyScope))
    }

    // endregion

    // region Package and target lookup

    fun `test a module's target is its Sources or Tests directory`() {
        val pkg = root.resolve("Pkg")
        assertEquals(SwiftBuildScope(pkg, null), SwiftPackageLocator.scopeOf(module("pkg", JAVA, "Pkg")))
        assertEquals(SwiftBuildScope(pkg, "Lib"), SwiftPackageLocator.scopeOf(module("lib", JAVA, "Pkg/Sources/Lib")))
        assertEquals(SwiftBuildScope(pkg, "LibTests"), SwiftPackageLocator.scopeOf(module("tests", JAVA, "Pkg/Tests/LibTests")))
        // Not a standard target directory: the package is built as a whole.
        assertEquals(SwiftBuildScope(pkg, null), SwiftPackageLocator.scopeOf(module("lint", JAVA, "Pkg/Plugins/Lint")))
        assertNull(SwiftPackageLocator.scopeOf(module("plain", JAVA, "plain")))
    }

    fun `test a file's target`() {
        val pkg = root.resolve("Pkg")
        assertEquals(SwiftBuildScope(pkg, "Lib"), SwiftPackageLocator.scopeOf(vfs(pkg.resolve("Sources/Lib/Lib.swift"))))
        assertEquals(SwiftBuildScope(pkg, null), SwiftPackageLocator.scopeOf(vfs(pkg.resolve("Package.swift"))))
        assertEquals(SwiftBuildScope(root.resolve("Outer/Inner"), "Inner"), SwiftPackageLocator.scopeOf(vfs(root.resolve("Outer/Inner/Sources/Inner/Inner.swift"))))
        assertNull(SwiftPackageLocator.scopeOf(vfs(root.resolve("plain/Main.java"))))
    }

    fun `test builds of a package are merged`() {
        val pkg = SwiftBuildScope(root.resolve("Pkg"), null)
        val lib = pkg.copy(target = "Lib")
        val tests = pkg.copy(target = "LibTests")
        val other = SwiftBuildScope(root.resolve("Outer/Inner"), "Inner")
        assertEquals(
            listOf(SwiftBuildRequest(pkg, buildTests = true, clean = true), SwiftBuildRequest(other)),
            SwiftBuildRequest.merge(listOf(SwiftBuildRequest(lib, clean = true), SwiftBuildRequest(pkg, buildTests = true), SwiftBuildRequest(other))),
        )
        // Targets alone are built one by one, after a single clean.
        assertEquals(
            listOf(SwiftBuildRequest(lib, clean = true), SwiftBuildRequest(tests)),
            SwiftBuildRequest.merge(listOf(SwiftBuildRequest(lib, clean = true), SwiftBuildRequest(tests, clean = true), SwiftBuildRequest(lib))),
        )
    }

    // endregion

    // region Real builds

    /** Records the Build tool window events instead of showing them. */
    private class RecordingBuildViewManager(project: Project) : BuildViewManager(project) {
        val events: MutableList<BuildEvent> = Collections.synchronizedList(ArrayList())
        override fun onEvent(buildId: Any, event: BuildEvent) {
            events += event
        }
    }

    private fun recordBuildEvents(): RecordingBuildViewManager =
        RecordingBuildViewManager(project).also { project.replaceService(BuildViewManager::class.java, it, testRootDisposable) }

    @OptIn(ExperimentalPathApi::class)
    private fun copyFixture(vararg names: String): Path {
        val fixtures = Path.of("src/test/testData/swiftpm").toAbsolutePath()
        for (name in names) {
            val source = listOf(fixtures.resolve(name), fixtures.resolve("build-output/packages/$name")).first(Files::isDirectory)
            source.copyToRecursively(root.resolve(name), followLinks = false) { src, dst ->
                if (src.name == ".build") CopyActionResult.SKIP_SUBTREE else src.copyToIgnoringExistingDirectory(dst, followLinks = false)
            }
        }
        return root.resolve(names.first())
    }

    private fun toolchainAvailable(): Boolean {
        if (SwiftToolchainSettings.toolchain() != null) return true
        println("Skipped ${name}: no Swift toolchain found")
        return false
    }

    fun `test builds a package`() {
        if (!toolchainAvailable()) return
        copyFixture("Greeter", "Utils")
        val module = module("Greeter", SwiftPackageLocator.SWIFT_MODULE_TYPE_ID, "Greeter")
        val view = recordBuildEvents()

        val result = PlatformTestUtil.waitForPromise(ProjectTaskManager.getInstance(project).build(module), BUILD_TIMEOUT_MS)!!

        val events = view.events.toList()
        assertFalse(events.joinToString("\n") { it.message }, result.hasErrors())
        assertFalse(result.isAborted)
        assertTrue(events.first() is StartBuildEvent)
        val finish = events.last() as FinishBuildEvent
        assertTrue(finish.result is SuccessResult)
        assertEquals(listOf("Package Greeter"), events.filterIsInstance<StartEvent>().filter { it !is StartBuildEvent }.map { it.message })
        assertTrue(events.filterIsInstance<FinishEvent>().filter { it !is FinishBuildEvent }.all { it.result is SuccessResult })
        val output = events.filterIsInstance<OutputBuildEvent>().joinToString("") { it.message }
        assertTrue(output, "build --package-path" in output && "--build-tests" in output)
        assertTrue(output, "Build complete!" in output)
        assertEmpty(events.filterIsInstance<MessageEvent>().filter { it.kind == MessageEvent.Kind.ERROR })
    }

    fun `test a failing build reports navigable errors`() {
        if (!toolchainAvailable()) return
        copyFixture("Broken")
        val module = module("Broken", SwiftPackageLocator.SWIFT_MODULE_TYPE_ID, "Broken/Sources/Broken")
        val view = recordBuildEvents()

        val result = PlatformTestUtil.waitForPromise(ProjectTaskManager.getInstance(project).build(module), BUILD_TIMEOUT_MS)!!

        val events = view.events.toList()
        assertTrue(result.hasErrors())
        assertTrue((events.last() as FinishBuildEvent).result is FailureResult)
        assertEquals(listOf("Target Broken (Broken)"), events.filterIsInstance<StartEvent>().filter { it !is StartBuildEvent }.map { it.message })
        val errors = events.filterIsInstance<FileMessageEvent>().filter { it.kind == MessageEvent.Kind.ERROR }
        assertEquals(
            setOf("cannot convert value of type 'Int' to specified type 'String'", "missing argument for parameter 'suffix' in call",
                "cannot convert return expression of type 'String' to return type 'Int'"),
            errors.map { it.message }.toSet(),
        )
        val broken = root.resolve("Broken/Sources/Broken/Broken.swift")
        assertTrue(errors.all { it.filePosition.path == broken })
        assertTrue(events.filterIsInstance<FileMessageEvent>().any { it.kind == MessageEvent.Kind.WARNING && it.filePosition.startLine == 4 })
    }

    fun `test rebuild cleans first`() {
        if (!toolchainAvailable()) return
        copyFixture("Utils")
        val module = module("Utils", SwiftPackageLocator.SWIFT_MODULE_TYPE_ID, "Utils")
        val view = recordBuildEvents()

        val result = PlatformTestUtil.waitForPromise(ProjectTaskManager.getInstance(project).rebuild(module), BUILD_TIMEOUT_MS)!!

        val events = view.events.toList()
        assertFalse(result.hasErrors())
        assertEquals(listOf("Package Utils, clean"), events.filterIsInstance<StartEvent>().filter { it !is StartBuildEvent }.map { it.message })
        val output = events.filterIsInstance<OutputBuildEvent>().joinToString("") { it.message }
        val clean = output.indexOf(" clean\n")
        val build = output.indexOf(" build --package-path")
        assertTrue(output, clean in 0..<build)
    }

    fun `test stop cancels the build`() {
        if (!toolchainAvailable()) return
        copyFixture("Greeter", "Utils")
        val module = module("Greeter", SwiftPackageLocator.SWIFT_MODULE_TYPE_ID, "Greeter")
        val view = recordBuildEvents()

        val promise = ProjectTaskManager.getInstance(project).build(module)
        // Stop once `swift build` runs, as the Build tool window's Stop button does.
        val deadline = System.currentTimeMillis() + BUILD_TIMEOUT_MS
        while (view.events.count { it is OutputBuildEvent } < 2 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val start = view.events.first() as StartBuildEvent
        (start.buildDescriptor as DefaultBuildDescriptor).processHandler!!.destroyProcess()
        val result = PlatformTestUtil.waitForPromise(promise, BUILD_TIMEOUT_MS)!!

        assertTrue(result.isAborted)
        val finish = view.events.last() as FinishBuildEvent
        assertTrue(finish.result is SkippedResult)
        assertEquals("Build cancelled", finish.message)
    }

    // endregion

    private companion object {
        const val JAVA = "JAVA_MODULE"
        const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
