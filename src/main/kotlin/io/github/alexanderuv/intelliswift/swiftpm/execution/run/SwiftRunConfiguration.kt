package io.github.alexanderuv.intelliswift.swiftpm.execution.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
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
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftExecutionSupport
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftFileLocationFilter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** *Swift Run*: `swift run <product>` of a SwiftPM package. */
class SwiftRunConfigurationType : ConfigurationTypeBase(
    ID, "Swift Run", "Runs an executable product of a SwiftPM package with swift run",
    NotNullLazyValue.createValue { SwiftIcons.FILE },
) {
    init {
        addFactory(SwiftRunConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "SwiftRunConfiguration"
    }
}

class SwiftRunConfigurationFactory(type: SwiftRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = SwiftRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        SwiftRunConfiguration(project, this, "Swift Run").apply { packageDirectory = project.basePath }

    override fun getOptionsClass(): Class<out BaseState> = SwiftRunConfigurationOptions::class.java
}

class SwiftRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var packageDirectory by string()
    var product by string()
    var programArguments by string()
    var workingDirectory by string()
    var environment by linkedMap<String, String>()
    var passParentEnvironment by property(true)
    var buildConfiguration by enum(SwiftBuildConfiguration.DEBUG)
}

class SwiftRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<SwiftRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): SwiftRunConfigurationOptions = super.getOptions() as SwiftRunConfigurationOptions

    var packageDirectory: String?
        get() = options.packageDirectory
        set(value) { options.packageDirectory = value?.trim()?.ifEmpty { null } }

    /** The executable product; null runs the package's only executable. */
    var product: String?
        get() = options.product
        set(value) { options.product = value?.trim()?.ifEmpty { null } }

    var programArguments: String?
        get() = options.programArguments
        set(value) { options.programArguments = value?.ifBlank { null } }

    /** The program's working directory; null means the package directory. */
    var workingDirectory: String?
        get() = options.workingDirectory
        set(value) { options.workingDirectory = value?.trim()?.ifEmpty { null } }

    var environment: Map<String, String>
        get() = options.environment
        set(value) { options.environment = LinkedHashMap(value) }

    var passParentEnvironment: Boolean
        get() = options.passParentEnvironment
        set(value) { options.passParentEnvironment = value }

    var buildConfiguration: SwiftBuildConfiguration
        get() = options.buildConfiguration
        set(value) { options.buildConfiguration = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SwiftRunSettingsEditor(project)

    @Throws(RuntimeConfigurationException::class)
    override fun checkConfiguration() {
        SwiftExecutionSupport.checkPackageDirectory(packageDirectory)
        checkProductForArguments()
        workingDirectory?.let { dir ->
            val path = try {
                Path.of(dir)
            } catch (_: InvalidPathException) {
                null
            }
            if (path == null || !Files.isDirectory(path)) throw RuntimeConfigurationError("The working directory $dir does not exist.")
        }
    }

    /** `swift run` takes the first argument for the product if none is named. */
    fun checkProductForArguments() {
        if (product == null && !programArguments.isNullOrBlank()) {
            throw RuntimeConfigurationError("Set the executable product to pass program arguments.")
        }
    }

    override fun suggestedName(): String? = product

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = SwiftRunState(environment, this)
}

/** Runs `swift run`, which builds the product first, in a console that can stop it. */
class SwiftRunState(environment: ExecutionEnvironment, private val configuration: SwiftRunConfiguration) : CommandLineState(environment) {
    init {
        addConsoleFilters(SwiftFileLocationFilter(environment.project, SwiftExecutionSupport.printedPathMapper(configuration.packageDirectory)))
    }

    override fun startProcess(): ProcessHandler {
        val packageDir = try {
            configuration.checkProductForArguments()
            SwiftExecutionSupport.checkPackageDirectory(configuration.packageDirectory)
        } catch (e: RuntimeConfigurationException) {
            throw ExecutionException(e.localizedMessage)
        }
        // `swift run` runs the program in its own working directory, which also picks the toolchain
        // when `swift` is a proxy such as swiftly.
        val workingDirectory = try {
            configuration.workingDirectory?.let(Path::of) ?: packageDir
        } catch (_: InvalidPathException) {
            throw ExecutionException("The working directory ${configuration.workingDirectory} is not a valid path.")
        }
        val toolchain = SwiftExecutionSupport.toolchain(environment.project, workingDirectory).toolchain
        val command = SwiftPackageCommands(toolchain, packageDir, workingDirectory).run(
            product = configuration.product,
            configuration = configuration.buildConfiguration,
            programArgs = ParametersListUtil.parse(configuration.programArguments.orEmpty()),
        )
        SwiftExecutionSupport.applyEnvironment(command, configuration.environment, configuration.passParentEnvironment)
        // Stopping sends SIGINT first, so the program (and swift run) can exit cleanly, then kills it.
        val handler = KillableColoredProcessHandler(command)
        handler.setShouldKillProcessSoftly(true)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
