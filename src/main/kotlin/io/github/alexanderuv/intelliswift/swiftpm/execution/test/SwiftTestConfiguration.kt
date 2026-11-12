package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftExecutionSupport
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath
import java.nio.file.Files
import java.nio.file.Path

/** *Swift Test*: `swift test` of a SwiftPM package, with the results in a test tree. */
class SwiftTestConfigurationType : ConfigurationTypeBase(
    ID, "Swift Test", "Runs the XCTest and Swift Testing tests of a SwiftPM package with swift test",
    NotNullLazyValue.createValue { SwiftIcons.FILE },
) {
    init {
        addFactory(SwiftTestConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "SwiftTestConfiguration"
    }
}

class SwiftTestConfigurationFactory(type: SwiftTestConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = SwiftTestConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        SwiftTestConfiguration(project, this, "Swift Test").apply { packageDirectory = project.basePath }

    override fun getOptionsClass(): Class<out BaseState> = SwiftTestConfigurationOptions::class.java
}

/** Which tests a Swift Test configuration runs. */
enum class SwiftTestScope(val displayName: String) {
    ALL("All tests"),
    TARGET("Test target"),
    SUITE("Class or suite"),
    TEST("Test"),
}

class SwiftTestConfigurationOptions : LocatableRunConfigurationOptions() {
    var packageDirectory by string()
    var scope by enum(SwiftTestScope.ALL)
    var testModule by string()
    var testSuite by string()
    var testName by string()
    var extraArguments by string()
    var environment by linkedMap<String, String>()
    var passParentEnvironment by property(true)
    var buildConfiguration by enum(SwiftBuildConfiguration.DEBUG)
}

class SwiftTestConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<SwiftTestConfigurationOptions>(project, factory, name) {

    override fun getOptions(): SwiftTestConfigurationOptions = super.getOptions() as SwiftTestConfigurationOptions

    var packageDirectory: String?
        get() = options.packageDirectory
        set(value) { options.packageDirectory = value.clean() }

    var scope: SwiftTestScope
        get() = options.scope
        set(value) { options.scope = value }

    /** The test target's module name (scopes other than [SwiftTestScope.ALL]). */
    var testModule: String?
        get() = options.testModule
        set(value) { options.testModule = value.clean() }

    /**
     * The XCTest class, or the Swift Testing suite path (`Outer/Inner`), for
     * [SwiftTestScope.SUITE]; for [SwiftTestScope.TEST], the test's class or suite, empty for a
     * top-level Swift Testing function.
     */
    var testSuite: String?
        get() = options.testSuite
        set(value) { options.testSuite = value.clean() }

    /** The XCTest method or Swift Testing function (`names(name:)`) for [SwiftTestScope.TEST]. */
    var testName: String?
        get() = options.testName
        set(value) { options.testName = value.clean() }

    var extraArguments: String?
        get() = options.extraArguments
        set(value) { options.extraArguments = value?.ifBlank { null } }

    var environment: Map<String, String>
        get() = options.environment
        set(value) { options.environment = LinkedHashMap(value) }

    var passParentEnvironment: Boolean
        get() = options.passParentEnvironment
        set(value) { options.passParentEnvironment = value }

    var buildConfiguration: SwiftBuildConfiguration
        get() = options.buildConfiguration
        set(value) { options.buildConfiguration = value }

    /** The `swift test --filter` for the scope, or null to run all tests. */
    val filter: String? get() = filterOf(scope, testModule, testSuite, testName)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SwiftTestSettingsEditor(project)

    @Throws(RuntimeConfigurationException::class)
    override fun checkConfiguration() {
        SwiftExecutionSupport.checkPackageDirectory(packageDirectory)
        if (scope != SwiftTestScope.ALL && testModule == null) throw RuntimeConfigurationError("The test target is not set.")
        if (scope == SwiftTestScope.SUITE && testSuite == null) throw RuntimeConfigurationError("The class or suite is not set.")
        if (scope == SwiftTestScope.TEST && testName == null) throw RuntimeConfigurationError("The test is not set.")
    }

    override fun suggestedName(): String? = when (scope) {
        SwiftTestScope.ALL -> null
        SwiftTestScope.TARGET -> testModule
        SwiftTestScope.SUITE -> testSuite
        SwiftTestScope.TEST -> listOfNotNull(testSuite, testName).joinToString(".")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        SwiftTestRunState(environment, this, filtersOverride = null)

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        /**
         * The `swift test --filter` for a scope: `swift test` searches it in `Module.Class/method`
         * (XCTest) and in Swift Testing IDs (`Module.Suite/function(label:)/File.swift:1:2`).
         */
        fun filterOf(scope: SwiftTestScope, module: String?, suite: String?, name: String?): String? {
            if (scope == SwiftTestScope.ALL || module.isNullOrBlank()) return null
            val prefix = "^" + SwiftTestPath.escapeRegex(module) + "\\."
            val suitePath = suite?.takeIf { it.isNotBlank() }?.let(SwiftTestPath::escapeRegex)
            return when (scope) {
                SwiftTestScope.ALL -> null
                SwiftTestScope.TARGET -> prefix
                SwiftTestScope.SUITE -> suitePath?.let { "$prefix$it(/|$)" }
                SwiftTestScope.TEST -> name?.takeIf { it.isNotBlank() }?.let { test ->
                    prefix + (suitePath?.let { "$it/" } ?: "") + SwiftTestPath.escapeRegex(test) + "(/|$)"
                }
            }
        }
    }
}

/**
 * Runs `swift test` with the Swift Testing event stream written to a temporary file, and shows
 * the results of both test frameworks in a test tree ([SwiftTestConsoleProperties]).
 * [filtersOverride] replaces the configuration's filter, to rerun failed tests.
 */
class SwiftTestRunState(
    environment: ExecutionEnvironment,
    private val configuration: SwiftTestConfiguration,
    private val filtersOverride: List<String>?,
) : CommandLineState(environment) {

    private var eventStream: Path? = null

    override fun startProcess(): ProcessHandler {
        val packageDir = try {
            SwiftExecutionSupport.checkPackageDirectory(configuration.packageDirectory)
        } catch (e: RuntimeConfigurationException) {
            throw ExecutionException(e.localizedMessage)
        }
        val toolchain = SwiftExecutionSupport.toolchain(environment.project, packageDir)
        val directory = Files.createTempDirectory("intelliswift-test")
        val stream = directory.resolve("events.jsonl").also { eventStream = it }
        val command = SwiftPackageCommands(toolchain.toolchain, packageDir).test(
            filters = filtersOverride ?: listOfNotNull(configuration.filter),
            configuration = configuration.buildConfiguration,
            extraArgs = SwiftTestingEventStream.arguments(toolchain.version, stream) +
                ParametersListUtil.parse(configuration.extraArguments.orEmpty()),
        )
        SwiftExecutionSupport.applyEnvironment(command, configuration.environment, configuration.passParentEnvironment)
        // Swift Testing's console output uses SF Symbols (private-use characters outside Apple
        // fonts) on macOS; plain Unicode symbols display in the IDE console.
        if (SF_SYMBOLS !in configuration.environment) command.withEnvironment(SF_SYMBOLS, "0")
        val handler = try {
            KillableColoredProcessHandler(command)
        } catch (e: ExecutionException) {
            SwiftTestEventsConverter.deleteTemporaryDirectory(directory)
            throw e
        }
        handler.setShouldKillProcessSoftly(true)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = SwiftTestConsoleProperties(configuration, executor, eventStream)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(SwiftTestConsoleProperties.FRAMEWORK_NAME, handler, properties)
        val result = DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
        val rerunFailed = properties.createRerunFailedTestsAction(console)
        if (console is SMTRunnerConsoleView) rerunFailed.setModelProvider { console.resultsViewer }
        result.setRestartActions(rerunFailed, ToggleAutoTestAction())
        return result
    }

    private companion object {
        const val SF_SYMBOLS = "SWT_SF_SYMBOLS_ENABLED"
    }
}
