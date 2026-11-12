package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath
import kotlin.math.roundToLong

/**
 * Turns XCTest's console output into test tree events. XCTest has no machine-readable live
 * output; it prints one line per event, in two formats:
 *
 * - Apple's XCTest (macOS): `Test Case '-[Module.Class method]' started.`, then
 *   `... passed (0.001 seconds).` / `failed` / `skipped`, and failures as
 *   `path:line: error: -[Module.Class method] : message`.
 * - swift-corelibs-xctest (Linux, Windows): `Test Case 'Class.method' started at <date>`, then
 *   `... passed (0.001 seconds)`, and failures as `path:line: error: Class.method : message`.
 *   The module is not printed; [moduleOf] resolves it from the class name, if it can.
 *
 * Skips are printed like failures without `error:`. XCTest runs tests one at a time, so any other
 * line printed while a test runs is that test's output.
 */
class XCTestOutputParser(
    private val tree: SwiftTestTree,
    private val moduleOf: (className: String) -> String? = { null },
    /** Maps the physical paths of failure locations to the project's; see [SwiftTestEventsConverter]. */
    private val mapPath: (String) -> String = { it },
) {
    private class Running(val nodeId: String, val name: String) {
        val failures = ArrayList<SwiftTestTree.Issue>()
        var skipReason: String? = null
    }

    private var running: Running? = null

    /** Modules resolved for corelibs class names, so each class is looked up once. */
    private val resolvedModules = HashMap<String, String?>()

    /** Handles one output [line] (without its line break). Returns false if it is not XCTest's and no test is running. */
    fun line(line: String, stderr: Boolean = false): Boolean {
        CASE_STARTED.matchEntire(line)?.let { m ->
            start(testName(m))
            return true
        }
        CASE_FINISHED.matchEntire(line)?.let { m ->
            finish(testName(m), m.groups["verb"]!!.value, m.groups["seconds"]!!.value)
            return true
        }
        SUITE_FINISHED.matchEntire(line)?.let { m ->
            // The class suite ends; other suites (the bundle, "All tests") have no node.
            if (running == null && openClassPath?.className == m.groups["suite"]!!.value) closeOpenClass()
            return false
        }
        ISSUE.matchEntire(line)?.let { m ->
            val name = testName(m)
            val current = running
            if (current != null && current.name == name.display) {
                val location = "${mapPath(m.groups["file"]!!.value)}:${m.groups["line"]!!.value}"
                val message = m.groups["message"]!!.value.trimEnd().removeSuffix(" -").trimEnd()
                if (m.groups["error"] != null) {
                    current.failures += SwiftTestTree.Issue(message, location)
                } else {
                    current.skipReason = message.removePrefix("Test skipped").removePrefix(" - ").ifBlank { "Skipped" }
                }
                return true
            }
        }
        val current = running ?: return false
        tree.output(current.nodeId, line + "\n", stderr)
        return true
    }

    /** Ends the run: a test still running did not finish (the process crashed or was stopped). */
    fun finish() {
        abandonRunning("The test did not finish: the test process exited while it was running")
        closeOpenClass()
    }

    /** Fails the running test, which will not finish, with the failures it recorded and [reason]. */
    private fun abandonRunning(reason: String) {
        val current = running ?: return
        running = null
        tree.finishTest(current.nodeId, null, SwiftTestTree.Failure.of(current.failures + SwiftTestTree.Issue(reason, null)))
    }

    private class Name(val module: String?, val className: String, val method: String, val display: String)

    private fun testName(m: MatchResult): Name {
        val objc = m.groups["objc"]
        if (objc != null) {
            val qualified = m.groups["qualified"]!!.value
            val module = qualified.substringBefore('.', "").ifEmpty { null }
            val className = if (module != null) qualified.substringAfter('.') else qualified
            return Name(module, className, m.groups["selector"]!!.value, objc.value)
        }
        val className = m.groups["class"]!!.value
        val module = resolvedModules.getOrPut(className) { moduleOf(className) }
        return Name(module, className, m.groups["method"]!!.value, m.groups["plain"]!!.value)
    }

    private fun start(name: Name) {
        // XCTest runs one test at a time: a test still running when another starts never finished.
        abandonRunning("The test did not finish")
        val classPath = SwiftTestPath.XCTest(name.module, name.className)
        val testPath = classPath.copy(method = name.method)
        if (openClassPath != classPath) {
            closeOpenClass()
            val parent = name.module?.let(tree::module) ?: SwiftTestEvent.ROOT_ID
            val node = newClassNode(classPath)
            tree.startSuite(node, parent, name.className, classPath.locationUrl)
            openClass = node
            openClassPath = classPath
        }
        val classNode = openClass!!
        val nodeId = "$classNode/${name.method}"
        tree.startTest(nodeId, classNode, name.method, testPath.locationUrl)
        running = Running(nodeId, name.display)
    }

    /**
     * A node ID for a class that starts running. XCTest runs each class's tests together, so a class
     * that runs again is another one: corelibs XCTest names classes without their module, and test
     * targets may declare classes of the same name. Each run of a class gets its own node.
     */
    private fun newClassNode(path: SwiftTestPath.XCTest): String {
        val base = "xctest:" + path.locationUrl.substringAfter("://")
        if (!tree.wasStarted(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base#$it" }.first { !tree.wasStarted(it) }
    }

    /** The class whose tests are running; closed when its suite ends, another class starts, or the run ends. */
    private var openClass: String? = null
    private var openClassPath: SwiftTestPath.XCTest? = null

    private fun closeOpenClass() {
        openClass?.let(tree::finishSuite)
        openClass = null
        openClassPath = null
    }

    private fun finish(name: Name, verb: String, seconds: String) {
        val current = running?.takeIf { it.name == name.display } ?: return
        running = null
        val duration = seconds.toDoubleOrNull()?.let { (it * 1000).roundToLong() }
        when (verb) {
            "failed" -> tree.finishTest(current.nodeId, duration,
                SwiftTestTree.Failure.of(current.failures) ?: SwiftTestTree.Failure("Test failed", ""))
            "skipped" -> tree.finishTest(current.nodeId, duration, ignoredReason = current.skipReason ?: "Skipped")
            // XCTest can record an issue and still pass, e.g. for an expected failure.
            else -> tree.finishTest(current.nodeId, duration)
        }
    }

    private companion object {
        /** `-[Module.Class method]` (Apple) or `Class.method` (corelibs). */
        const val NAME = """(?:(?<objc>-\[(?<qualified>[^\s\]]+) (?<selector>[^\s\]]+)\])|(?<plain>(?<class>[^\s'.]+(?:\.[^\s'.]+)*?)\.(?<method>[^\s'.]+)))"""

        val CASE_STARTED = Regex("""Test Case '$NAME' started(?:\.| at .*)?""")
        val CASE_FINISHED = Regex("""Test Case '$NAME' (?<verb>passed|failed|skipped) \((?<seconds>\d+(?:\.\d+)?) seconds\)\.?""")
        val SUITE_FINISHED = Regex("""Test Suite '(?<suite>.+)' (?:passed|failed) at .*""")
        val ISSUE = Regex("""(?<file>.+?):(?<line>\d+): (?<error>error: )?$NAME : (?<message>.*)""")
    }
}
