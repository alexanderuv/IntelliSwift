package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftFileLocationFilter
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath
import java.nio.file.Path

/**
 * The test tree of a Swift Test run: an id-based tree built by [SwiftTestEventsConverter], with
 * nodes located by [SwiftTestLocator], failure locations that navigate to the source, and
 * *Rerun Failed Tests*.
 */
class SwiftTestConsoleProperties(
    private val configuration: SwiftTestConfiguration,
    executor: Executor,
    private val eventStream: Path?,
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = true
        isPrintTestingStartedTime = false
        addStackTraceFilter(SwiftFileLocationFilter(configuration.project))
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        SwiftTestEventsConverter(testFrameworkName, consoleProperties, eventStream, ::xcTestModuleOf)

    override fun getTestLocator(): SMTestLocator = SwiftTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        SwiftRerunFailedTestsAction(consoleView, this)

    /** A failure's stack trace starts with `path:line[:column]` of the failed assertion. */
    override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? {
        val match = FAILURE_LOCATION.find(stacktrace) ?: return super.getErrorNavigatable(location, stacktrace)
        val file = LocalFileSystem.getInstance().findFileByPath(match.groups[1]!!.value) ?: return super.getErrorNavigatable(location, stacktrace)
        val line = match.groups[2]!!.value.toInt() - 1
        val column = (match.groups[3]?.value?.toInt() ?: 1) - 1
        return OpenFileDescriptor(project, file, line, column)
    }

    /**
     * The module of an XCTest class, for corelibs XCTest (Linux, Windows), whose output names
     * only the class: see [SwiftTestLocator.xcTestModuleOf].
     */
    private fun xcTestModuleOf(className: String): String? {
        val directory = configuration.packageDirectory?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
        return ReadAction.nonBlocking<String?> {
            if (DumbService.isDumb(project)) return@nonBlocking null
            val scope: GlobalSearchScope = GlobalSearchScopesCore.directoryScope(project, directory, true)
            SwiftTestLocator.xcTestModuleOf(project, scope, className)
        }.expireWith(project).executeSynchronously()
    }

    companion object {
        const val FRAMEWORK_NAME = "SwiftTest"

        /** The first `path:line[:column]` in a failure's details. */
        private val FAILURE_LOCATION = Regex("""^\s*(.+?\.swift):(\d+)(?::(\d+))?""", RegexOption.MULTILINE)
    }
}

/** Reruns the failed tests of a Swift Test run, each with its own `--filter`. */
class SwiftRerunFailedTestsAction(componentContainer: ComponentContainer, properties: SwiftTestConsoleProperties) :
    AbstractRerunFailedTestsAction(componentContainer) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? SwiftTestConfiguration ?: return null
        // Suites containing failed tests are failed too; rerun only the tests.
        val filters = failedTestFilters(getFailedTests(configuration.project).filter { it.isLeaf }.mapNotNull { it.locationUrl })
        if (filters.isEmpty()) return null
        return object : MyRunProfile(configuration) {
            override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState =
                SwiftTestRunState(env, configuration, filters)
        }
    }

    companion object {
        /** One filter per failed test; parameterized cases rerun their function. */
        fun failedTestFilters(locationUrls: List<String>): List<String> =
            locationUrls.mapNotNull { url -> SwiftTestPath.parse(url)?.takeIf { it !is SwiftTestPath.Target }?.filter }.distinct()
    }
}
