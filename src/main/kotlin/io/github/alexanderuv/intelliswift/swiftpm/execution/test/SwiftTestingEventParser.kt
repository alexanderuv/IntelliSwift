package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath
import kotlin.math.roundToLong

/**
 * Turns Swift Testing's JSON event stream (`swift test --event-stream-output-path <file>
 * --event-stream-version 0`, one JSON object per line) into test tree events.
 *
 * The stream first describes every test (`{"kind":"test","payload":{id, kind: suite|function,
 * name, displayName?, sourceLocation, isParameterized}}`), then reports events
 * (`{"kind":"event","payload":{kind, testID, _testCase?, instant, messages, issue?}}`):
 * `testStarted` / `testEnded` for suites and functions, `testCaseStarted` / `testCaseEnded` for
 * each argument of a parameterized function, `issueRecorded`, `testSkipped`, `runStarted` and
 * `runEnded`. Tests run in parallel, so events of different tests interleave.
 *
 * A test ID is `Module.Suite/Inner/function(label:)/File.swift:line:column` (a suite's has no
 * location). Suites become suite nodes, parameterized functions too (with a child per case), other
 * functions test nodes, all under the node of their test target.
 */
class SwiftTestingEventParser(
    private val tree: SwiftTestTree,
    /** Maps the physical source paths of the stream to the project's; see [SwiftTestEventsConverter]. */
    private val mapPath: (String) -> String = { it },
) {

    private class Record(
        val id: String,
        val isSuite: Boolean,
        val name: String,
        val isParameterized: Boolean,
        /** `path:line:column` of the declaration. */
        val location: String?,
    )

    private val records = LinkedHashMap<String, Record>()

    /** Start instants (seconds) of running nodes. */
    private val starts = HashMap<String, Double>()

    /** Issues recorded on running nodes. */
    private val issues = HashMap<String, MutableList<SwiftTestTree.Issue>>()

    /** Started, not yet ended case nodes of each parameterized function node. */
    private val openCases = HashMap<String, MutableSet<String>>()

    /** Node IDs of the current run are prefixed so that a second run in the same stream gets new nodes. */
    private var runs = 0
    private var prefix = "swift-testing:"

    /** Handles one line of the event stream. Lines that are not JSON objects are ignored. */
    fun line(line: String) {
        val text = line.trim()
        if (!text.startsWith("{")) return
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val payload = json.obj("payload") ?: return
        when (json.str("kind")) {
            "test" -> record(payload)
            "event" -> event(payload)
        }
    }

    private fun record(p: JsonObject) {
        val id = p.str("id") ?: return
        val location = p.obj("sourceLocation")?.let(::location)
        records[id] = Record(
            id = id,
            isSuite = p.str("kind") == "suite",
            name = p.str("displayName") ?: p.str("name") ?: lastComponent(id),
            isParameterized = p.bool("isParameterized"),
            location = location,
        )
    }

    private fun event(p: JsonObject) {
        val instant = p.obj("instant")?.num("absolute")
        val testId = p.str("testID")
        when (p.str("kind")) {
            "runStarted" -> {
                runs++
                if (runs > 1) {
                    prefix = "swift-testing$runs:"
                    starts.clear(); issues.clear(); openCases.clear(); caseNumbers.clear(); failedWithSuite.clear()
                }
            }
            "testStarted" -> if (testId != null) {
                val node = start(testId)
                if (instant != null) starts[node] = instant
            }
            "testCaseStarted" -> if (testId != null) caseStarted(testId, p, instant)
            "testCaseEnded" -> if (testId != null) caseEnded(testId, p, instant)
            "issueRecorded" -> issueRecorded(testId, p)
            "testEnded" -> if (testId != null) ended(testId, instant)
            "testSkipped" -> if (testId != null) skipped(testId, p)
        }
    }

    // region Nodes

    private fun nodeId(testId: String) = prefix + testId

    private fun isSuite(testId: String) = records[testId]?.isSuite ?: !SOURCE_LOCATION.containsMatchIn(testId)

    private fun isParameterized(testId: String) = records[testId]?.isParameterized == true

    /** Starts the node of [testId] and any parent suites that are not started yet; returns its node ID. */
    private fun start(testId: String): String {
        val node = nodeId(testId)
        if (tree.wasStarted(node)) return node
        val parent = parentNode(testId)
        val path = SwiftTestPath.parseTestingId(testId)
        val record = records[testId]
        val name = record?.name ?: lastComponent(testId)
        if (isSuite(testId) || isParameterized(testId)) {
            tree.startSuite(node, parent, name, path?.locationUrl, record?.location)
        } else {
            tree.startTest(node, parent, name, path?.locationUrl, record?.location)
        }
        return node
    }

    private fun parentNode(testId: String): String {
        val withoutLocation = if (isSuite(testId)) testId else SOURCE_LOCATION.replace(testId, "")
        val slash = withoutLocation.lastIndexOf('/')
        if (slash > 0) return start(withoutLocation.substring(0, slash))
        return tree.module(testId.substringBefore('.'))
    }

    /**
     * The node of a test case: its function's node and the case's number, in the order the cases
     * appear. A case is identified by its `id` (its arguments' IDs), which is long.
     */
    private fun caseNode(functionNode: String, case: JsonObject): String {
        val numbers = caseNumbers.getOrPut(functionNode) { HashMap() }
        val key = case.str("id") ?: case.str("displayName") ?: ""
        return functionNode + "#" + numbers.getOrPut(key) { numbers.size + 1 }
    }

    private val caseNumbers = HashMap<String, MutableMap<String, Int>>()

    private fun caseStarted(testId: String, p: JsonObject, instant: Double?) {
        if (!isParameterized(testId)) return
        val case = p.obj("_testCase") ?: return
        val functionNode = start(testId)
        val node = caseNode(functionNode, case)
        val displayName = case.str("displayName") ?: case.str("id") ?: "case"
        val url = SwiftTestPath.parseTestingId(testId)?.let { it.locationUrl + SwiftTestPath.CASE_SEPARATOR + displayName }
        tree.startTest(node, functionNode, displayName, url, records[testId]?.location)
        openCases.getOrPut(functionNode) { LinkedHashSet() } += node
        if (instant != null) starts[node] = instant
    }

    private fun caseEnded(testId: String, p: JsonObject, instant: Double?) {
        if (!isParameterized(testId)) return
        val case = p.obj("_testCase") ?: return
        val functionNode = nodeId(testId)
        val node = caseNode(functionNode, case)
        openCases[functionNode]?.remove(node)
        finishTest(node, instant)
    }

    private fun finishTest(node: String, instant: Double?) {
        tree.finishTest(node, duration(node, instant), SwiftTestTree.Failure.of(issues.remove(node).orEmpty()))
    }

    private fun ended(testId: String, instant: Double?) {
        val node = nodeId(testId)
        if (!tree.isOpen(node)) return
        if (isSuite(testId) || isParameterized(testId)) {
            openCases.remove(node)?.forEach { finishTest(it, null) }
            reportIssuesOfSuite(testId, node)
            starts.remove(node)
            tree.finishSuite(node)
        } else {
            finishTest(node, instant)
        }
    }

    /** A suite node cannot fail by itself, so issues recorded on it (e.g. a throwing `init`) get a failed child. */
    private fun reportIssuesOfSuite(testId: String, node: String) {
        val recorded = issues.remove(node) ?: return
        val child = "$node#issues"
        tree.startTest(child, node, "Recorded issue", SwiftTestPath.parseTestingId(testId)?.locationUrl, records[testId]?.location)
        tree.finishTest(child, 0, SwiftTestTree.Failure.of(recorded))
    }

    private fun skipped(testId: String, p: JsonObject) {
        val reason = p.arr("_comments")?.mapNotNull { it.stringOrNull() }?.joinToString("\n")?.ifBlank { null }
            ?: p.arr("messages")?.firstOrNull()?.asJsonObjectOrNull()?.str("text")
            ?: "Skipped"
        if (!isSuite(testId)) {
            skip(testId, reason)
            return
        }
        // A skipped suite reports only itself; report each of its tests as skipped.
        start(testId)
        for (record in records.values) {
            if (!record.isSuite && isInside(record.id, testId)) skip(record.id, reason)
        }
        val nested = records.values.filter { it.isSuite && isInside(it.id, testId) }.map { it.id }
        for (suite in nested.sortedByDescending { id -> id.count { it == '/' } }) tree.finishSuite(nodeId(suite))
        tree.finishSuite(nodeId(testId))
    }

    private fun skip(testId: String, reason: String) {
        val node = nodeId(testId)
        if (isParameterized(testId)) {
            // A parameterized function is a suite node; report the skip on a child.
            start(testId)
            tree.startTest("$node#skipped", node, "Skipped")
            tree.finishTest("$node#skipped", 0, ignoredReason = reason)
            tree.finishSuite(node)
            return
        }
        start(testId)
        tree.finishTest(node, 0, ignoredReason = reason)
    }

    private fun isInside(id: String, suiteId: String) = id.startsWith("$suiteId/")

    private fun duration(node: String, end: Double?): Long? {
        val start = starts.remove(node) ?: return null
        if (end == null) return null
        return ((end - start) * 1000).roundToLong().coerceAtLeast(0)
    }

    // endregion

    private fun issueRecorded(testId: String?, p: JsonObject) {
        val messages = p.arr("messages")?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty()
        val issue = p.obj("issue")
        val location = (issue?.obj("sourceLocation") ?: p.obj("_sourceLocation"))?.let(::location)
        val summary = messages.firstOrNull()?.str("text") ?: "Issue recorded"
        val details = messages.drop(1).mapNotNull { m ->
            val text = m.str("text") ?: return@mapNotNull null
            "  ".repeat(m.num("_indentation")?.toInt() ?: 0) + text
        }
        val known = issue?.bool("isKnown") == true
        // Already reported, with the issue of the suite whose traits failed.
        if (testId != null && nodeId(testId) in failedWithSuite) return
        if (testId != null && !known && !tree.wasStarted(nodeId(testId))) {
            // A test whose arguments or traits fail to evaluate (`@Test(arguments: try …)`,
            // `.enabled(if: try …)`) never starts or ends: the issue is all the stream says of it.
            failUnstarted(testId, SwiftTestTree.Issue(summary, location, details))
            return
        }
        val node = testId?.let { targetNode(it, p) }
        if (node == null || known) {
            // Known issues (`withKnownIssue`) do not fail the test; show them as its output.
            val text = buildString {
                if (issue?.bool("isKnown") == true) append("Known issue: ")
                append(summary)
                if (location != null) append(" (").append(location).append(')')
                for (line in details) append('\n').append("  ").append(line)
                append('\n')
            }
            if (node != null) tree.output(node, text, stderr = false) else tree.output("", text, stderr = true)
            return
        }
        issues.getOrPut(node) { ArrayList() } += SwiftTestTree.Issue(summary, location, details)
    }

    /**
     * Reports [testId], which never started, as failed with [issue]. A suite's condition traits
     * apply to its tests too, so none of them starts either: Swift Testing reports the suite's
     * issue, then the same issue for each of its tests. They are all failed here, before the suite
     * node is finished (no node can start under a finished one), and their own copies of the issue
     * are dropped when they arrive.
     */
    private fun failUnstarted(testId: String, issue: SwiftTestTree.Issue) {
        val node = start(testId)
        when {
            isSuite(testId) -> {
                issues[node] = mutableListOf(issue)
                reportIssuesOfSuite(testId, node)
                for (record in records.values) {
                    if (record.isSuite || !isInside(record.id, testId) || tree.wasStarted(nodeId(record.id))) continue
                    failUnstarted(record.id, issue)
                    failedWithSuite += nodeId(record.id)
                }
                val nested = records.values.filter { it.isSuite && isInside(it.id, testId) }.map { it.id }
                for (suite in nested.sortedByDescending { id -> id.count { it == '/' } }) {
                    failedWithSuite += nodeId(suite)
                    tree.finishSuite(nodeId(suite))
                }
                tree.finishSuite(node)
            }
            isParameterized(testId) -> {
                issues[node] = mutableListOf(issue)
                reportIssuesOfSuite(testId, node)
                tree.finishSuite(node)
            }
            else -> tree.finishTest(node, null, SwiftTestTree.Failure.of(listOf(issue)))
        }
    }

    /** Nodes of tests and nested suites failed with their suite by [failUnstarted]. */
    private val failedWithSuite = HashSet<String>()

    /** The node an issue of [testId] belongs to: the running case for a parameterized function, else the test or suite. */
    private fun targetNode(testId: String, p: JsonObject): String? {
        val node = nodeId(testId)
        if (!tree.isOpen(node)) return null
        val case = p.obj("_testCase")
        if (case != null && isParameterized(testId)) {
            val caseNode = caseNode(node, case)
            if (tree.isOpen(caseNode)) return caseNode
        }
        return node
    }

    private fun location(o: JsonObject): String? {
        // `fileID` (`Module/File.swift`) is no path, so it is kept as it is.
        val file = (o.str("_filePath") ?: o.str("filePath"))?.let(mapPath) ?: o.str("fileID") ?: return null
        val line = o.num("line")?.toInt() ?: return file
        val column = o.num("column")?.toInt()
        return if (column != null) "$file:$line:$column" else "$file:$line"
    }

    private companion object {
        /** The `/File.swift:line:column` that ends a function's test ID. */
        val SOURCE_LOCATION = Regex("""/[^/]+:\d+:\d+$""")

        fun lastComponent(id: String): String {
            val path = SOURCE_LOCATION.replace(id, "")
            val last = path.substringAfterLast('/')
            return if (last == path) path.substringAfter('.') else last
        }

        fun JsonObject.str(key: String): String? = get(key)?.stringOrNull()
        fun JsonObject.num(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        fun JsonObject.obj(key: String): JsonObject? = get(key)?.asJsonObjectOrNull()
        fun JsonObject.arr(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
        fun JsonElement.stringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    }
}
