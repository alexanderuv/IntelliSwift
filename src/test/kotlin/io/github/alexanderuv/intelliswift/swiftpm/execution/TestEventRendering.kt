package io.github.alexanderuv.intelliswift.swiftpm.execution

import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestEvent
import java.nio.file.Path

/** Test helpers: captured `swift test` output and a one-line rendering of test tree events. */
object TestEventRendering {
    val TEST_OUTPUT: Path = Path.of("src/test/testData/swiftpm/test-output")

    fun sample(name: String): String = TEST_OUTPUT.resolve(name).toFile().readText()

    fun render(events: List<SwiftTestEvent>): String = events.joinToString("\n") { render(it) }

    fun render(event: SwiftTestEvent): String = when (event) {
        is SwiftTestEvent.SuiteStarted -> "suite+ ${event.nodeId} <- ${event.parentId} '${event.name}'" + extras(event.locationUrl, event.metainfo)
        is SwiftTestEvent.SuiteFinished -> "suite- ${event.nodeId}"
        is SwiftTestEvent.TestStarted -> "test+ ${event.nodeId} <- ${event.parentId} '${event.name}'" + extras(event.locationUrl, event.metainfo)
        is SwiftTestEvent.TestFailed -> "failed ${event.nodeId} '${event.message}' [${event.details.replace("\n", " | ")}]"
        is SwiftTestEvent.TestIgnored -> "ignored ${event.nodeId} '${event.message}'"
        is SwiftTestEvent.TestFinished -> "test- ${event.nodeId} ${event.durationMs?.let { "${it}ms" } ?: "-"}"
        is SwiftTestEvent.TestOutput -> "out ${event.nodeId} ${if (event.stderr) "err " else ""}'${event.text.trimEnd()}'"
        is SwiftTestEvent.RunOutput -> "run-out ${if (event.stderr) "err " else ""}'${event.text.trimEnd()}'"
    }

    private fun extras(url: String?, meta: String?) = (url?.let { " $it" } ?: "") + (meta?.let { " @$it" } ?: "")
}
