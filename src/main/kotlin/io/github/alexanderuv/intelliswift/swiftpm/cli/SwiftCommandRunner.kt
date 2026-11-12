package io.github.alexanderuv.intelliswift.swiftpm.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Key

/** A `swift` command that failed; [output] holds what it printed. */
class SwiftCommandException(message: String, val output: ProcessOutput? = null) : Exception(message)

/** Runs `swift` commands to completion, with cancellation and optional live output. */
object SwiftCommandRunner {

    /**
     * Runs [command] and returns its output, whatever the exit code. [cancelled] is checked before
     * the process starts and polled while it runs; when it returns true the process is destroyed
     * (or never started) and [ProcessCanceledException] is thrown. [onOutput] receives output as
     * it arrives.
     */
    fun run(
        command: GeneralCommandLine,
        cancelled: () -> Boolean = { false },
        onOutput: ((text: String, outputType: Key<*>) -> Unit)? = null,
    ): ProcessOutput {
        if (cancelled()) throw ProcessCanceledException()
        val handler = OSProcessHandler(command)
        val capture = CapturingProcessAdapter()
        handler.addProcessListener(capture)
        if (onOutput != null) {
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = onOutput(event.text, outputType)
            })
        }
        handler.startNotify()
        while (!handler.waitFor(POLL_MS)) {
            if (cancelled()) {
                handler.destroyProcess()
                handler.waitFor(POLL_MS * 20)
                throw ProcessCanceledException()
            }
        }
        return capture.output
    }

    /** Runs [command] and returns its standard output; a non-zero exit is a [SwiftCommandException]. */
    fun runChecked(
        command: GeneralCommandLine,
        cancelled: () -> Boolean = { false },
        onOutput: ((text: String, outputType: Key<*>) -> Unit)? = null,
    ): String {
        val output = run(command, cancelled, onOutput)
        if (output.exitCode != 0) {
            val details = output.stderr.ifBlank { output.stdout }.trim()
            throw SwiftCommandException("`${command.commandLineString}` failed (exit code ${output.exitCode})" +
                if (details.isNotEmpty()) ":\n$details" else "", output)
        }
        return output.stdout
    }

    private const val POLL_MS = 100L
}
