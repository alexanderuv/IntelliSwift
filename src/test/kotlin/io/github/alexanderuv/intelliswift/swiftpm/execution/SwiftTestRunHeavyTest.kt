package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.swiftpm.execution.run.SwiftRunConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.run.SwiftRunConfigurationType
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftRerunFailedTestsAction
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfigurationType
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConsoleProperties
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestRunState
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the Greeter fixture's tests (`src/test/testData/swiftpm/Greeter`, copied to a temporary
 * directory with the `Utils` package it depends on) with `swift test` through a Swift Test
 * configuration, and checks the test tree. Skipped when no Swift toolchain is installed.
 */
@RunWith(JUnit4::class)
class SwiftTestRunHeavyTest {
    private lateinit var fixture: CodeInsightTestFixture
    private lateinit var workspace: Path
    private lateinit var packageRoot: VirtualFile
    private val disposables = ArrayList<ExecutionResult>()

    @Before
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "SwiftTestRunHeavyTest").fixture,
        )
        runInEdtAndWait { fixture.setUp() }
        val toolchain = SwiftToolchainSettings.toolchain()
        if (toolchain == null || toolchain.version == null) {
            runInEdtAndWait { fixture.tearDown() }
            assumeTrue("No Swift toolchain detected", false)
        }
        workspace = Files.createTempDirectory("swift-test-run")
        for (name in listOf("Greeter", "Utils")) {
            FileUtil.copyDir(Path.of("src/test/testData/swiftpm", name).toFile(), workspace.resolve(name).toFile()) { it.name != ".build" }
        }
        packageRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.resolve("Greeter"))!!
        runInEdtAndWait { PsiTestUtil.addContentRoot(fixture.module, packageRoot) }
        IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    }

    @After
    fun tearDown() {
        if (!::workspace.isInitialized) return
        try {
            runInEdtAndWait {
                disposables.forEach { result -> result.executionConsole?.let(Disposer::dispose) }
                PsiTestUtil.removeContentEntry(fixture.module, packageRoot)
                fixture.tearDown()
            }
        } finally {
            NioFiles.deleteRecursively(workspace)
        }
    }

    private fun testConfiguration(): SwiftTestConfiguration {
        val factory = ConfigurationTypeUtil.findConfigurationType(SwiftTestConfigurationType::class.java).factory
        return SwiftTestConfiguration(fixture.project, factory, "Greeter tests").apply { packageDirectory = packageRoot.path }
    }

    private class TestRun(val root: SMTestProxy.SMRootTestProxy, val properties: SwiftTestConsoleProperties, val console: SMTRunnerConsoleView)

    /** Runs [profile] (the configuration, or a rerun of failed tests) and returns the finished tree. */
    private fun runTests(profile: RunProfile): TestRun {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val runner = ProgramRunner.getRunner(executor.id, testConfiguration())!!
        val environment = ExecutionEnvironmentBuilder(fixture.project, executor).runProfile(profile).runner(runner).build()
        val state = profile.getState(executor, environment) as SwiftTestRunState
        val result = runInEdtAndGet { state.execute(executor, runner) }
        disposables += result
        val handler = result.processHandler!!
        handler.startNotify()
        assertTrue("swift test did not finish", handler.waitFor(TimeUnit.MINUTES.toMillis(10)))
        val console = result.executionConsole as SMTRunnerConsoleView
        val root = console.resultsViewer.testsRootNode
        val deadline = System.currentTimeMillis() + 60_000
        while (root.isInProgress || !root.isFinal) {
            assertTrue("The test tree was not finished", System.currentTimeMillis() < deadline)
            runInEdtAndWait { dispatchAllEventsInIdeEventQueue() }
            Thread.sleep(20)
        }
        return TestRun(root, console.properties as SwiftTestConsoleProperties, console)
    }

    private fun SMTestProxy.child(name: String): SMTestProxy =
        children.firstOrNull { it.name == name } ?: throw AssertionError("No '$name' under '${this.name}': ${children.map { it.name }}")

    private fun SMTestProxy.state(): String = when {
        isIgnored -> "ignored"
        isDefect -> "failed"
        isPassed -> "passed"
        else -> "other"
    }

    @Test
    fun `swift test results form a tree of targets, classes, suites and cases`() {
        val run = runTests(testConfiguration())
        val root = run.root
        val properties = run.properties

        val target = root.child("GreeterTests")
        assertEquals(setOf("GreeterXCTests", "GreeterSuite", "Edge cases", "topLevel()"), target.children.map { it.name }.toSet())

        val leaves = root.allTests.filter { it.isLeaf }.associate { "${it.parent.name}/${it.name}" to it.state() }
        assertEquals(mapOf(
            "GreeterXCTests/testFailing" to "failed",
            "GreeterXCTests/testGreeting" to "passed",
            "GreeterXCTests/testSkipped" to "ignored",
            "GreeterSuite/loudGreeting()" to "passed",
            "names/\"Ada\"" to "passed",
            "names/\"Grace\"" to "passed",
            "GreeterTests/topLevel()" to "passed",
            "Edge cases/failingExpectation()" to "failed",
            "Edge cases/disabled()" to "ignored",
        ), leaves)
        assertEquals(5, leaves.values.count { it == "passed" })
        assertEquals(2, leaves.values.count { it == "failed" })

        // The parameterized function has a child per argument.
        val names = target.child("GreeterSuite").child("names")
        assertTrue(names.isSuite)
        assertEquals(listOf("\"Ada\"", "\"Grace\""), names.children.map { it.name }.sorted())
        for (leaf in root.allTests.filter { it.isLeaf && !it.isIgnored }) assertNotNull("${leaf.name} has no duration", leaf.duration)

        // Failures navigate to the failed expectation, and nodes to their declarations.
        val failing = target.child("Edge cases").child("failingExpectation()")
        assertTrue(failing.errorMessage, failing.errorMessage!!.startsWith("Expectation failed: Greeter(name: \"Ada\").greeting() == \"nope\""))
        val scope = GlobalSearchScope.allScope(fixture.project)
        runInEdtAndWait {
            val location = failing.getLocation(fixture.project, scope)!!
            assertEquals("failingExpectation", (location.psiElement as SwiftNamedDeclaration).name)
            val navigatable = properties.getErrorNavigatable(location, failing.stacktrace!!) as OpenFileDescriptor
            assertEquals("GreeterTests.swift", navigatable.file.name)
            assertEquals(20, navigatable.line)
            assertEquals(8, navigatable.column)

            val xcFailing = target.child("GreeterXCTests").child("testFailing")
            assertTrue(xcFailing.stacktrace!!.contains("GreeterXCTests.swift:10"))
            assertEquals("testFailing", (xcFailing.getLocation(fixture.project, scope)!!.psiElement as SwiftNamedDeclaration).name)
            assertEquals("GreeterXCTests", (target.child("GreeterXCTests").getLocation(fixture.project, scope)!!.psiElement as SwiftNamedDeclaration).name)
            assertEquals("names", (names.child("\"Ada\"").getLocation(fixture.project, scope)!!.psiElement as SwiftNamedDeclaration).name)
            assertEquals("GreeterSuite", (target.child("GreeterSuite").getLocation(fixture.project, scope)!!.psiElement as SwiftNamedDeclaration).name)
        }

        // Rerun Failed Tests runs exactly the failed tests.
        val rerunProfile = runInEdtAndGet {
            val action = properties.createRerunFailedTestsAction(run.console)
            action.setModelProvider { run.console.resultsViewer }
            val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), testConfiguration()).build()
            action.getRunProfileTestAccessor(environment)
        }
        assertNotNull("Nothing to rerun", rerunProfile)
        val rerun = runTests(rerunProfile!!).root
        assertEquals(
            setOf("GreeterXCTests/testFailing", "Edge cases/failingExpectation()"),
            rerun.allTests.filter { it.isLeaf }.map { "${it.parent.name}/${it.name}" }.toSet(),
        )
    }

    @Test
    fun `swift run runs the executable product with its arguments`() {
        val factory = ConfigurationTypeUtil.findConfigurationType(SwiftRunConfigurationType::class.java).factory
        val configuration = SwiftRunConfiguration(fixture.project, factory, "greet").apply {
            packageDirectory = packageRoot.path
            product = "greet"
            programArguments = "Grace"
        }
        configuration.checkConfiguration()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
        val state = configuration.getState(executor, environment)
        val result = runInEdtAndGet { state.execute(executor, ProgramRunner.getRunner(executor.id, configuration)!!) }!!
        disposables += result
        val output = CapturingProcessAdapter()
        result.processHandler.addProcessListener(output)
        result.processHandler.startNotify()
        assertTrue(result.processHandler.waitFor(TimeUnit.MINUTES.toMillis(10)))
        assertEquals(output.output.stderr, 0, output.output.exitCode)
        assertTrue(output.output.stdout, output.output.stdout.lines().contains("Hello, Grace."))
    }
}
