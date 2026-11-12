package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.NioFiles
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import java.io.IOException
import java.nio.file.Path

/**
 * Builds the test tree of a `swift test` run from two sources: the process output, which
 * carries XCTest's results ([XCTestOutputParser]), and Swift Testing's event stream file, which
 * is followed while the process runs ([EventStreamTailer], [SwiftTestingEventParser]).
 *
 * Both sources go through [process]: the event stream with its own output key, which the base
 * class passes through unbuffered to [processServiceMessages]. There each line is turned into
 * service messages, under one lock, since output arrives on the stdout and stderr reader threads
 * and the tailer's thread. When the process ends, the rest of the event stream is read and every
 * open node is finished.
 */
class SwiftTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    /** The event stream file, or null if the toolchain cannot write one. Its directory is deleted at the end. */
    private val eventStream: Path?,
    xcTestModuleOf: (className: String) -> String?,
    /** Maps the source paths the frameworks print (physical ones) to the project's. */
    mapPath: (String) -> String = { it },
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    private val lock = Any()
    private val pendingEvents = ArrayList<SwiftTestEvent>()
    private val tree = SwiftTestTree { pendingEvents += it }
    private val xcTest = XCTestOutputParser(tree, xcTestModuleOf, mapPath)
    private val swiftTesting = SwiftTestingEventParser(tree, mapPath)
    private val tailer = eventStream?.let { file -> EventStreamTailer(file, { line -> process(line, EVENT_STREAM) }) }
    private var exitCode: Int? = null

    override fun onStartTesting() {
        super.onStartTesting()
        tailer?.start()
    }

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean =
        synchronized(lock) {
            when (outputType) {
                EVENT_STREAM -> {
                    swiftTesting.line(text)
                    flush(visitor)
                    true
                }
                END_OF_RUN -> {
                    xcTest.finish()
                    tree.finishAll(SwiftTestTree.Failure(
                        "The test did not finish: the test process exited" + (exitCode?.let { " with exit code $it" } ?: ""), ""))
                    flush(visitor)
                    true
                }
                else -> {
                    if (super.processServiceMessages(text, outputType, visitor)) return true
                    // The IDE's own messages (the command line, the exit code) are shown as they are.
                    if ((outputType as? ProcessOutputType)?.baseOutputType == ProcessOutputType.SYSTEM) return false
                    val stderr = ProcessOutputType.isStderr(outputType)
                    for (line in lines(text)) {
                        if (!xcTest.line(line.trimEnd('\n', '\r'), stderr)) pendingEvents += SwiftTestEvent.RunOutput(line, stderr)
                    }
                    flush(visitor)
                    true
                }
            }
        }

    /** [text] split after each line break; the output splitter normally passes one line at a time. */
    private fun lines(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
            result += text.substring(start, end)
            start = end
        }
        return result
    }

    private fun flush(visitor: ServiceMessageVisitor) {
        val events = pendingEvents.toList()
        pendingEvents.clear()
        for (event in events) super.processServiceMessages(event.toServiceMessage(), ProcessOutputType.STDOUT, visitor)
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        this.exitCode = exitCode
        tailer?.stopAndDrain()
        process("end of run", END_OF_RUN)
        eventStream?.parent?.let(::deleteTemporaryDirectory)
    }

    override fun dispose() {
        tailer?.stop()
        super.dispose()
    }

    companion object {
        private val LOG = logger<SwiftTestEventsConverter>()

        /** Deletes the temporary directory of a run's event stream. */
        fun deleteTemporaryDirectory(directory: Path) {
            try {
                NioFiles.deleteRecursively(directory)
            } catch (e: IOException) {
                LOG.warn("Cannot delete $directory", e)
            }
        }

        /** Output keys that are not [ProcessOutputType]s, which the base class passes on unbuffered and unsplit. */
        private val EVENT_STREAM = Key.create<Any>("SwiftTesting.EventStream")
        private val END_OF_RUN = Key.create<Any>("SwiftTest.EndOfRun")
    }
}
