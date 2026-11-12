package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/**
 * A change to the test tree, produced from `swift test` output. Nodes are identified by
 * [nodeId] and attached to [SuiteStarted.parentId] / [TestStarted.parentId], as in the SM
 * runner's id-based tree ([ROOT_ID] is the root). [toServiceMessage] renders an event as the
 * TeamCity service message the SM runner consumes.
 */
sealed interface SwiftTestEvent {

    data class SuiteStarted(
        val nodeId: String,
        val parentId: String,
        val name: String,
        val locationUrl: String? = null,
        val metainfo: String? = null,
    ) : SwiftTestEvent

    data class SuiteFinished(val nodeId: String, val name: String) : SwiftTestEvent

    data class TestStarted(
        val nodeId: String,
        val parentId: String,
        val name: String,
        val locationUrl: String? = null,
        val metainfo: String? = null,
    ) : SwiftTestEvent

    /** [details] is shown as the stack trace: one `path:line[:column]` location per line, then extra details. */
    data class TestFailed(val nodeId: String, val name: String, val message: String, val details: String) : SwiftTestEvent

    data class TestIgnored(val nodeId: String, val name: String, val message: String) : SwiftTestEvent

    /** [durationMs] is null when the runner reported no duration. */
    data class TestFinished(val nodeId: String, val name: String, val durationMs: Long?) : SwiftTestEvent

    /** Output that belongs to one test. */
    data class TestOutput(val nodeId: String, val name: String, val text: String, val stderr: Boolean) : SwiftTestEvent

    /** Output that belongs to no test; it goes to the root of the tree. */
    data class RunOutput(val text: String, val stderr: Boolean) : SwiftTestEvent

    companion object {
        const val ROOT_ID = "0"
    }
}

/** This event as a service message. */
fun SwiftTestEvent.toServiceMessage(): String {
    val builder = when (this) {
        is SwiftTestEvent.SuiteStarted -> ServiceMessageBuilder.testSuiteStarted(name)
            .node(nodeId, parentId).optional("locationHint", locationUrl).optional("metainfo", metainfo)
        is SwiftTestEvent.SuiteFinished -> ServiceMessageBuilder.testSuiteFinished(name).node(nodeId)
        is SwiftTestEvent.TestStarted -> ServiceMessageBuilder.testStarted(name)
            .node(nodeId, parentId).optional("locationHint", locationUrl).optional("metainfo", metainfo)
        is SwiftTestEvent.TestFailed -> ServiceMessageBuilder.testFailed(name).node(nodeId)
            .addAttribute("message", message).addAttribute("details", details)
        is SwiftTestEvent.TestIgnored -> ServiceMessageBuilder.testIgnored(name).node(nodeId).addAttribute("message", message)
        is SwiftTestEvent.TestFinished -> ServiceMessageBuilder.testFinished(name).node(nodeId)
            .optional("duration", durationMs?.toString())
        is SwiftTestEvent.TestOutput -> (if (stderr) ServiceMessageBuilder.testStdErr(name) else ServiceMessageBuilder.testStdOut(name))
            .node(nodeId).addAttribute("out", text)
        // Sent to the root explicitly: as plain output it would go to whichever test is running.
        is SwiftTestEvent.RunOutput -> (if (stderr) ServiceMessageBuilder.testStdErr("") else ServiceMessageBuilder.testStdOut(""))
            .node(SwiftTestEvent.ROOT_ID).addAttribute("out", text)
    }
    return builder.toString()
}

private fun ServiceMessageBuilder.node(nodeId: String, parentId: String? = null): ServiceMessageBuilder {
    addAttribute("nodeId", nodeId)
    if (parentId != null) addAttribute("parentNodeId", parentId)
    return this
}

private fun ServiceMessageBuilder.optional(name: String, value: String?): ServiceMessageBuilder =
    if (value != null) addAttribute(name, value) else this
