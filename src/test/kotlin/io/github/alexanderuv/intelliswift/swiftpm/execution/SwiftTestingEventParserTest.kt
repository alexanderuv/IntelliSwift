package io.github.alexanderuv.intelliswift.swiftpm.execution

import io.github.alexanderuv.intelliswift.swiftpm.execution.TestEventRendering.render
import io.github.alexanderuv.intelliswift.swiftpm.execution.TestEventRendering.sample
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestEvent
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestTree
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestingEventParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Swift Testing event streams to test tree events, on streams captured with swift 6.4.2 on macOS
 * (`--event-stream-version 0`): `greeter.events.jsonl` from the Greeter fixture's tests,
 * `probe.events.jsonl` from `probe-Nested.swift` (nested suites, an implicit suite extended in
 * an extension, parameterized functions with a failing case, a known issue, thrown errors, a
 * disabled suite, a suite whose `init` throws). Paths are replaced by `/FIXTURE`. The expected
 * events, one per line, are in the `.expected.txt` files next to them.
 */
class SwiftTestingEventParserTest {

    private fun parse(stream: String): String {
        val events = ArrayList<SwiftTestEvent>()
        val tree = SwiftTestTree { events += it }
        val parser = SwiftTestingEventParser(tree)
        stream.lines().forEach(parser::line)
        tree.finishAll(SwiftTestTree.Failure("interrupted", ""))
        return render(events)
    }

    @Test fun `Greeter run gives suites, a parameterized function with its cases, a skip and a failure`() {
        assertEquals(sample("greeter.events.expected.txt").trimEnd(), parse(sample("greeter.events.jsonl")))
    }

    @Test fun `probe run gives nested suites, case failures, known issues and skipped suites`() {
        assertEquals(sample("probe.events.expected.txt").trimEnd(), parse(sample("probe.events.jsonl")))
    }

    private fun test(id: String, parameterized: Boolean = false): String {
        val path = id.substringBeforeLast('/')
        val name = path.substringAfterLast('/').let { if (it == path) it.substringAfter('.') else it }
        return """{"kind":"test","payload":{"id":"$id","kind":"function","name":"$name","isParameterized":$parameterized,"sourceLocation":{"_filePath":"/src/T.swift","line":3,"column":2}}}"""
    }

    private fun suite(id: String) = """{"kind":"test","payload":{"id":"$id","kind":"suite","name":"${id.substringAfterLast('/').substringAfter('.')}"}}"""

    private fun event(kind: String, testId: String?, at: Double = 1.0, extra: String = "") =
        """{"kind":"event","payload":{"kind":"$kind",${testId?.let { "\"testID\":\"$it\"," } ?: ""}"instant":{"absolute":$at},"messages":[{"symbol":"fail","text":"$kind text"}]$extra}}"""

    @Test fun `a skipped suite reports its tests as skipped even when the stream does not`() {
        val stream = listOf(
            suite("M.S"), suite("M.S/Inner"), test("M.S/a()/T.swift:3:2"), test("M.S/Inner/b()/T.swift:4:2"),
            event("runStarted", null),
            event("testSkipped", "M.S", extra = ""","_comments":["Off"]"""),
            event("runEnded", null),
        ).joinToString("\n")
        assertEquals("""
            suite+ target:M <- 0 'M' swift-target://M
            suite+ swift-testing:M.S <- target:M 'S' swift-testing://M.S
            test+ swift-testing:M.S/a()/T.swift:3:2 <- swift-testing:M.S 'a()' swift-testing://M.S/a() @/src/T.swift:3:2
            ignored swift-testing:M.S/a()/T.swift:3:2 'Off'
            test- swift-testing:M.S/a()/T.swift:3:2 0ms
            suite+ swift-testing:M.S/Inner <- swift-testing:M.S 'Inner' swift-testing://M.S/Inner
            test+ swift-testing:M.S/Inner/b()/T.swift:4:2 <- swift-testing:M.S/Inner 'b()' swift-testing://M.S/Inner/b() @/src/T.swift:3:2
            ignored swift-testing:M.S/Inner/b()/T.swift:4:2 'Off'
            test- swift-testing:M.S/Inner/b()/T.swift:4:2 0ms
            suite- swift-testing:M.S/Inner
            suite- swift-testing:M.S
            suite- target:M
        """.trimIndent(), parse(stream))
    }

    @Test fun `issues on a suite or a parameterized function itself fail a child node`() {
        val stream = listOf(
            suite("M.S"), test("M.S/p(x:)/T.swift:3:2", parameterized = true),
            event("testStarted", "M.S"),
            event("testStarted", "M.S/p(x:)/T.swift:3:2"),
            event("issueRecorded", "M.S/p(x:)/T.swift:3:2", extra = ""","issue":{"isKnown":false,"sourceLocation":{"_filePath":"/src/T.swift","line":5,"column":7}}"""),
            event("testEnded", "M.S/p(x:)/T.swift:3:2"),
            event("issueRecorded", "M.S", extra = ""","issue":{"isKnown":false}"""),
            event("testEnded", "M.S"),
            event("issueRecorded", null),
        ).joinToString("\n")
        assertEquals("""
            suite+ target:M <- 0 'M' swift-target://M
            suite+ swift-testing:M.S <- target:M 'S' swift-testing://M.S
            suite+ swift-testing:M.S/p(x:)/T.swift:3:2 <- swift-testing:M.S 'p(x:)' swift-testing://M.S/p(x:) @/src/T.swift:3:2
            test+ swift-testing:M.S/p(x:)/T.swift:3:2#issues <- swift-testing:M.S/p(x:)/T.swift:3:2 'Recorded issue' swift-testing://M.S/p(x:) @/src/T.swift:3:2
            failed swift-testing:M.S/p(x:)/T.swift:3:2#issues 'issueRecorded text' [/src/T.swift:5:7: issueRecorded text]
            test- swift-testing:M.S/p(x:)/T.swift:3:2#issues 0ms
            suite- swift-testing:M.S/p(x:)/T.swift:3:2
            test+ swift-testing:M.S#issues <- swift-testing:M.S 'Recorded issue' swift-testing://M.S
            failed swift-testing:M.S#issues 'issueRecorded text' [issueRecorded text]
            test- swift-testing:M.S#issues 0ms
            suite- swift-testing:M.S
            run-out err 'issueRecorded text'
            suite- target:M
        """.trimIndent(), parse(stream))
    }

    @Test fun `a second run in the same stream gets new nodes`() {
        val stream = listOf(
            test("M.t()/T.swift:3:2"),
            event("runStarted", null), event("testStarted", "M.t()/T.swift:3:2", 1.0), event("testEnded", "M.t()/T.swift:3:2", 1.25),
            event("runStarted", null), event("testStarted", "M.t()/T.swift:3:2", 2.0), event("testEnded", "M.t()/T.swift:3:2", 2.5),
        ).joinToString("\n")
        assertEquals("""
            suite+ target:M <- 0 'M' swift-target://M
            test+ swift-testing:M.t()/T.swift:3:2 <- target:M 't()' swift-testing://M.t() @/src/T.swift:3:2
            test- swift-testing:M.t()/T.swift:3:2 250ms
            test+ swift-testing2:M.t()/T.swift:3:2 <- target:M 't()' swift-testing://M.t() @/src/T.swift:3:2
            test- swift-testing2:M.t()/T.swift:3:2 500ms
            suite- target:M
        """.trimIndent(), parse(stream))
    }

    @Test fun `tests still running when the stream ends are failed`() {
        val stream = listOf(test("M.t()/T.swift:3:2"), event("testStarted", "M.t()/T.swift:3:2")).joinToString("\n")
        assertEquals("""
            suite+ target:M <- 0 'M' swift-target://M
            test+ swift-testing:M.t()/T.swift:3:2 <- target:M 't()' swift-testing://M.t() @/src/T.swift:3:2
            failed swift-testing:M.t()/T.swift:3:2 'interrupted' []
            test- swift-testing:M.t()/T.swift:3:2 -
            suite- target:M
        """.trimIndent(), parse(stream + "\nnot json\n{\"kind\":\"unknown\"}"))
    }
}
