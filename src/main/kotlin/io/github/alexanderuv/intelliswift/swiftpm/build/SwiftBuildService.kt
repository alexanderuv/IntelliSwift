package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.EventResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.FinishEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.StartEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SkippedResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.output.BuildOutputInstantReaderImpl
import com.intellij.build.process.BuildProcessHandler
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.task.ProjectTaskRunner
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandRunner
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmToolchains
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * One `swift build` of a build: of [scope], with the test targets when [buildTests], after
 * `swift package clean` when [clean].
 */
data class SwiftBuildRequest(val scope: SwiftBuildScope, val buildTests: Boolean = false, val clean: Boolean = false) {
    companion object {
        /**
         * The builds to run for [requests], in order: a package built as a whole needs no builds of
         * its targets, and a package is cleaned once, before its first build (cleaning removes the
         * whole package's build directory).
         */
        fun merge(requests: List<SwiftBuildRequest>): List<SwiftBuildRequest> =
            requests.groupBy { it.scope.packageDir }.flatMap { (packageDir, forPackage) ->
                val whole = forPackage.filter { it.scope.target == null }
                val builds = if (whole.isNotEmpty()) {
                    listOf(SwiftBuildRequest(SwiftBuildScope(packageDir, null), buildTests = whole.any { it.buildTests }))
                } else {
                    forPackage.distinctBy { it.scope.target }.map { SwiftBuildRequest(it.scope) }
                }
                val clean = forPackage.any { it.clean }
                builds.mapIndexed { i, build -> if (i == 0 && clean) build.copy(clean = true) else build }
            }
    }
}

/**
 * Runs `swift build` for the IDE's build actions and reports it in the Build tool window: one
 * entry per build, a node per package or target built, the output, the diagnostics parsed by
 * [SwiftBuildOutputParser], and a Stop button. In a project that is not trusted it asks to trust
 * it first, as syncs do, and builds nothing if the user declines.
 */
@Service(Service.Level.PROJECT)
class SwiftBuildService(private val project: Project, private val scope: CoroutineScope) {

    /** Starts building [requests] in the background; the promise is resolved when the build ends. */
    fun build(requests: List<SwiftBuildRequest>): Promise<ProjectTaskRunner.Result> {
        val promise = AsyncPromise<ProjectTaskRunner.Result>()
        val builds = SwiftBuildRequest.merge(requests)
        if (builds.isEmpty()) {
            promise.setResult(SwiftBuildResult.SUCCESS)
            return promise
        }
        val configuration = SwiftBuildSettings.getInstance(project).configuration
        val title = titleOf(builds)
        scope.launch {
            try {
                // `swift build` runs code from the packages: their manifests, and their build plugins.
                if (!ExternalSystemTrustedProjectDialog.confirmLoadingUntrustedProjectAsync(project, SwiftPm.SYSTEM_ID)) {
                    promise.setResult(SwiftBuildResult.ABORTED)
                    return@launch
                }
                val result = withBackgroundProgress(project, title) {
                    withContext(Dispatchers.IO) {
                        SwiftBuildRun(project, builds, configuration, title, coroutineContext.job).run()
                    }
                }
                promise.setResult(result)
            } catch (e: CancellationException) {
                promise.setResult(SwiftBuildResult.ABORTED)
                throw e
            } catch (e: Throwable) {
                LOG.error("Swift build failed", e)
                promise.setError(e)
            }
        }.invokeOnCompletion {
            // Cancelled before it started, as when the project closes.
            if (!promise.isDone) promise.setResult(SwiftBuildResult.ABORTED)
        }
        return promise
    }

    private fun titleOf(builds: List<SwiftBuildRequest>): String {
        val verb = if (builds.any { it.clean }) "Rebuild" else "Build"
        val single = builds.singleOrNull()
        return when {
            single == null -> "$verb " + builds.map { it.scope.packageName }.distinct().joinToString(", ")
            single.scope.target != null -> "$verb ${single.scope.target} (${single.scope.packageName})"
            else -> "$verb ${single.scope.packageName}"
        }
    }

    companion object {
        private val LOG = logger<SwiftBuildService>()

        fun getInstance(project: Project): SwiftBuildService = project.service()
    }
}

/** A [ProjectTaskRunner.Result] for a Swift build. */
enum class SwiftBuildResult(private val aborted: Boolean, private val errors: Boolean) : ProjectTaskRunner.Result {
    SUCCESS(false, false),
    FAILURE(false, true),
    ABORTED(true, false);

    override fun isAborted(): Boolean = aborted
    override fun hasErrors(): Boolean = errors
}

/** One build: the Build tool window entry and the `swift` processes behind it. Runs on a background thread. */
private class SwiftBuildRun(
    project: Project,
    private val builds: List<SwiftBuildRequest>,
    private val configuration: SwiftBuildConfiguration,
    private val title: String,
    private val job: Job,
) {
    private val buildId = Any()
    private val view: BuildProgressListener = project.service<BuildViewManager>()
    private val errors = AtomicInteger()
    private val listener = BuildProgressListener { id, event ->
        if (event is MessageEvent && event.kind == MessageEvent.Kind.ERROR) errors.incrementAndGet()
        view.onEvent(id, event)
    }
    private val processHandler = SwiftBuildProcessHandler(title)
    private val workingDir: Path =
        builds.map { it.scope.packageDir }.distinct().singleOrNull() ?: project.basePath?.let(Path::of) ?: builds.first().scope.packageDir

    private enum class Outcome { SUCCESS, FAILURE, CANCELLED }

    private val cancelled: Boolean get() = processHandler.isStopRequested || !job.isActive

    fun run(): ProjectTaskRunner.Result {
        val descriptor = DefaultBuildDescriptor(buildId, title, workingDir.toString(), System.currentTimeMillis())
            .withProcessHandler(processHandler, null)
        // Started here rather than by the Build tool window, so Stop works however the build is shown.
        processHandler.startNotify()
        send(StartBuildEvent.builder("Running…", descriptor).build())
        var outcome = Outcome.SUCCESS
        try {
            for (build in builds) {
                when (build(build)) {
                    Outcome.SUCCESS -> {}
                    // Keep building the other packages, so all their errors are reported.
                    Outcome.FAILURE -> outcome = Outcome.FAILURE
                    Outcome.CANCELLED -> {
                        outcome = Outcome.CANCELLED
                        break
                    }
                }
            }
        } finally {
            val (message, result) = when (outcome) {
                Outcome.SUCCESS -> "Build finished" to SuccessResultImpl()
                Outcome.FAILURE -> "Build failed" to FailureResultImpl()
                Outcome.CANCELLED -> "Build cancelled" to SkippedResultImpl()
            }
            send(FinishBuildEvent.builder(buildId, message, result).build())
            processHandler.terminate(if (outcome == Outcome.SUCCESS) 0 else 1)
        }
        return when (outcome) {
            Outcome.SUCCESS -> SwiftBuildResult.SUCCESS
            Outcome.FAILURE -> SwiftBuildResult.FAILURE
            Outcome.CANCELLED -> SwiftBuildResult.ABORTED
        }
    }

    private fun build(build: SwiftBuildRequest): Outcome {
        if (cancelled) return Outcome.CANCELLED
        val stepId = Any()
        val stepTitle = buildString {
            append(build.scope.target?.let { "Target $it (${build.scope.packageName})" } ?: "Package ${build.scope.packageName}")
            if (build.clean) append(", clean")
        }
        send(StartEvent.builder(stepId, stepTitle).withParentId(buildId).build())
        val outcome = toolchainFor(build, stepId)?.let { build(it, build, stepId) } ?: Outcome.FAILURE
        val result: EventResult = when (outcome) {
            Outcome.SUCCESS -> SuccessResultImpl()
            Outcome.FAILURE -> FailureResultImpl()
            Outcome.CANCELLED -> SkippedResultImpl()
        }
        send(FinishEvent.builder(stepId, stepTitle, result).withParentId(buildId).build())
        return outcome
    }

    /**
     * The toolchain [build] runs with, after the same checks as syncs and SwiftPM tasks: an
     * executable, runnable, supported `swift`. Checked per package, in its directory, since the
     * toolchain may be a proxy that picks one by the directory (swiftly's `.swift-version`).
     */
    private fun toolchainFor(build: SwiftBuildRequest, stepId: Any): SwiftToolchain? = try {
        SwiftPmToolchains.require(build.scope.packageDir)
    } catch (e: ExternalSystemException) {
        error(stepId, "No usable Swift toolchain", e.message.orEmpty())
        null
    }

    private fun build(toolchain: SwiftToolchain, build: SwiftBuildRequest, stepId: Any): Outcome {
        val errorsBefore = errors.get()
        val commands = SwiftPackageCommands(toolchain, build.scope.packageDir)
        return try {
            val cleanExit = if (build.clean) run(commands.clean(), stepId) else 0
            val exitCode = if (cleanExit == 0) {
                run(commands.build(configuration, target = build.scope.target, buildTests = build.buildTests), stepId)
            } else {
                cleanExit
            }
            if (exitCode != 0 && errors.get() == errorsBefore) {
                // A failure the output parser had nothing to say about (a crash, an unknown format).
                error(stepId, "swift exited with code $exitCode", "See the build output for details.")
            }
            if (exitCode == 0) Outcome.SUCCESS else Outcome.FAILURE
        } catch (_: ProcessCanceledException) {
            Outcome.CANCELLED
        } catch (e: ExecutionException) {
            error(stepId, "Cannot run ${toolchain.swift}", e.message.orEmpty())
            Outcome.FAILURE
        }
    }

    /** Runs [command], showing its output under [stepId] and parsing it; returns the exit code. */
    private fun run(command: GeneralCommandLine, stepId: Any): Int {
        // Plain text: the parser strips colors anyway, and the console would show the OSC 8 links as text.
        command.withEnvironment("NO_COLOR", "1")
        output("${command.commandLineString}\n", stepId, ProcessOutputType.SYSTEM)
        val reader = BuildOutputInstantReaderImpl(buildId, stepId, listener, listOf(SwiftBuildOutputParser()))
        try {
            return SwiftCommandRunner.run(command, cancelled = { cancelled }) { text, outputType ->
                if (!ProcessOutputType.isSystem(outputType) && text.isNotEmpty()) {
                    output(text, stepId, outputTypeOf(outputType))
                    reader.append(text)
                }
            }.exitCode
        } finally {
            // The reader parses complete lines only, and closing it does not complete the last one,
            // which either stream may have left open. When none is, this adds a blank line, which
            // the parser skips.
            reader.append('\n')
            // Waits until every line is parsed, so all messages precede the finish event. In 2026.2
            // `close()` does not wait; 2026.3 deprecates this in favour of a blocking `close()`.
            @Suppress("DEPRECATION")
            reader.closeAndGetFuture().join()
        }
    }

    private fun outputTypeOf(key: Key<*>): ProcessOutputType =
        if (ProcessOutputType.isStderr(key)) ProcessOutputType.STDERR else ProcessOutputType.STDOUT

    private fun output(text: String, stepId: Any, type: ProcessOutputType) {
        send(OutputBuildEvent.builder(text).withParentId(stepId).withOutputType(type).build())
    }

    private fun error(parentId: Any, title: String, description: String) {
        send(MessageEvent.builder(title, MessageEvent.Kind.ERROR).withParentId(parentId)
            .withGroup(SwiftBuildOutputParser.GROUP_SWIFTPM).withDescription(description).build())
    }

    private fun send(event: BuildEvent) = listener.onEvent(buildId, event)
}

/**
 * Stands for a build's `swift` processes in the Build tool window, whose Stop button destroys it:
 * that asks the build to stop, which destroys the running process.
 */
private class SwiftBuildProcessHandler(private val name: String) : BuildProcessHandler() {
    @Volatile
    var isStopRequested: Boolean = false
        private set

    override fun getExecutionName(): String = name

    override fun destroyProcessImpl() {
        isStopRequested = true
    }

    override fun detachProcessImpl() {
        isStopRequested = true
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    fun terminate(exitCode: Int) = notifyProcessTerminated(exitCode)
}
