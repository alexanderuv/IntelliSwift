package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath

/**
 * The nodes of one `swift test` run, shared by the XCTest output parser and the Swift Testing
 * event stream parser: both frameworks report into the same test target nodes. Tracks which
 * nodes are open so that suites are started before their children and every node is finished
 * exactly once; [finishAll] closes whatever is still open when the process ends.
 */
class SwiftTestTree(private val emit: (SwiftTestEvent) -> Unit) {

    private class Node(val id: String, val name: String, val isSuite: Boolean)

    /** Open nodes in the order they were started. */
    private val open = LinkedHashMap<String, Node>()
    private val seen = HashSet<String>()

    fun isOpen(nodeId: String): Boolean = nodeId in open

    fun wasStarted(nodeId: String): Boolean = nodeId in seen

    /** The node of test target [module], started on first use and kept open until [finishAll]. */
    fun module(module: String): String {
        val id = "target:$module"
        if (id !in seen) startSuite(id, SwiftTestEvent.ROOT_ID, module, SwiftTestPath.Target(module).locationUrl)
        return id
    }

    fun startSuite(id: String, parentId: String, name: String, locationUrl: String? = null, metainfo: String? = null) {
        if (!seen.add(id)) return
        open[id] = Node(id, name, isSuite = true)
        emit(SwiftTestEvent.SuiteStarted(id, parentId, name, locationUrl, metainfo))
    }

    fun startTest(id: String, parentId: String, name: String, locationUrl: String? = null, metainfo: String? = null) {
        if (!seen.add(id)) return
        open[id] = Node(id, name, isSuite = false)
        emit(SwiftTestEvent.TestStarted(id, parentId, name, locationUrl, metainfo))
    }

    fun output(id: String, text: String, stderr: Boolean) {
        val node = open[id]
        emit(if (node != null) SwiftTestEvent.TestOutput(id, node.name, text, stderr) else SwiftTestEvent.RunOutput(text, stderr))
    }

    /** Finishes test [id]: failed if [failure] is given, ignored if [ignoredReason] is. */
    fun finishTest(id: String, durationMs: Long?, failure: Failure? = null, ignoredReason: String? = null) {
        val node = open.remove(id) ?: return
        when {
            failure != null -> emit(SwiftTestEvent.TestFailed(id, node.name, failure.message, failure.details))
            ignoredReason != null -> emit(SwiftTestEvent.TestIgnored(id, node.name, ignoredReason))
        }
        emit(SwiftTestEvent.TestFinished(id, node.name, durationMs))
    }

    fun finishSuite(id: String) {
        val node = open.remove(id) ?: return
        emit(SwiftTestEvent.SuiteFinished(id, node.name))
    }

    /** Finishes every open node, innermost first; tests still running are reported as failed with [interruption]. */
    fun finishAll(interruption: Failure) {
        for (node in open.values.reversed().toList()) {
            if (node.isSuite) finishSuite(node.id) else finishTest(node.id, null, interruption)
        }
    }

    /** A failure: [message] is its summary, [details] the locations and further lines. */
    data class Failure(val message: String, val details: String) {
        companion object {
            /** Combines the failures recorded for one test into one. */
            fun of(issues: List<Issue>): Failure? {
                if (issues.isEmpty()) return null
                val message = if (issues.size == 1) issues[0].message else "${issues[0].message} (and ${issues.size - 1} more)"
                val details = issues.joinToString("\n") { issue ->
                    buildString {
                        if (issue.location != null) append(issue.location).append(": ")
                        append(issue.message)
                        for (line in issue.details) append('\n').append("    ").append(line)
                    }
                }
                return Failure(message, details)
            }
        }
    }

    /** One recorded failure; [location] is `path:line[:column]`. */
    data class Issue(val message: String, val location: String?, val details: List<String> = emptyList())
}
