package io.github.alexanderuv.intelliswift.swiftpm.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Key

/** A `swift` command that failed; [output] holds what it printed. */
class SwiftCommandException(message: String, val output: ProcessOutput? = null) : Exception(message)

/** Runs `swift` commands to completion, with cancellation and optional live output. */
object SwiftCommandRunner {

    /**
     * Runs [command] and returns its output, whatever the exit code. [cancelled] is checked before
     * the process starts and polled while it runs; when it returns true the process is destroyed
     * (or never started) and [ProcessCanceledException] is thrown.
     *
     * [onOutput] receives the output as it arrives, one whole line at a time (with its line break;
     * a last line without one comes when the process ends) and one call at a time, all before this
     * returns. Its `outputType` is the stream's: `STDOUT`, `STDERR` or `SYSTEM`.
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
        if (onOutput != null) handler.addProcessListener(Lines(onOutput))
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

    /**
     * Passes a process's output on in whole lines, one call at a time. The process handler reads
     * standard output and standard error on two threads and passes chunks on as they come, so a
     * consumer would otherwise be called from both at once and could get one stream's partial line
     * run into the other's. The platform calls [processTerminated] after the last output, before
     * the process counts as finished.
     */
    private class Lines(private val consumer: (text: String, outputType: Key<*>) -> Unit) : ProcessListener {
        /** Per stream, the text after its last line break. Guarded by `this`. */
        private val partial = LinkedHashMap<Key<*>, StringBuilder>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = synchronized(this) {
            val stream = (outputType as? ProcessOutputType)?.baseOutputType ?: outputType
            val buffer = partial.getOrPut(stream) { StringBuilder() }.append(event.text)
            var start = 0
            while (true) {
                val end = buffer.indexOf("\n", start)
                if (end < 0) break
                consumer(buffer.substring(start, end + 1), stream)
                start = end + 1
            }
            buffer.delete(0, start)
            Unit
        }

        override fun processTerminated(event: ProcessEvent) = synchronized(this) {
            for ((stream, rest) in partial) if (rest.isNotEmpty()) consumer(rest.toString(), stream)
            partial.clear()
        }
    }
}
