package io.github.alexanderuv.intelliswift.swiftpm.execution

import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftRerunFailedTestsAction
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestEvent
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestScope
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestingEventStream
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.toServiceMessage
import io.github.alexanderuv.intelliswift.toolchain.SwiftVersion
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** Test URLs, `swift test --filter` expressions, event stream flags and service messages. */
class SwiftTestPathTest {

    /** The strings `swift test` matches a filter against: XCTest `Module.Class/method`, Swift Testing IDs. */
    private val specifiers = listOf(
        "GreeterTests.GreeterXCTests/testGreeting",
        "GreeterTests.GreeterXCTests/testGreetingLoud",
        "GreeterTests.GreeterXCTestsMore/testOther",
        "GreeterTests.GreeterSuite/names(name:)/GreeterTests.swift:9:6",
        "GreeterTests.GreeterSuite/namesAgain()/GreeterTests.swift:12:6",
        "GreeterTests.GreeterSuite/Inner/deep()/GreeterTests.swift:14:10",
        "GreeterTests.topLevel()/GreeterTests.swift:15:2",
        "OtherTests.GreeterXCTests/testGreeting",
    )

    private fun selected(filter: String): List<String> = specifiers.filter { Regex(filter).containsMatchIn(it) }

    @Test fun `XCTest filters select a class or one method`() {
        assertEquals(specifiers.subList(0, 2), selected(SwiftTestPath.XCTest("GreeterTests", "GreeterXCTests").filter))
        assertEquals(specifiers.subList(0, 1), selected(SwiftTestPath.XCTest("GreeterTests", "GreeterXCTests", "testGreeting").filter))
        // Without a module (corelibs XCTest output), the class in any module.
        assertEquals(listOf(specifiers[0], specifiers[7]), selected(SwiftTestPath.XCTest(null, "GreeterXCTests", "testGreeting").filter))
    }

    @Test fun `Swift Testing filters select a suite with its nested suites, or one function`() {
        assertEquals(specifiers.subList(3, 6), selected(SwiftTestPath.SwiftTesting("GreeterTests", listOf("GreeterSuite")).filter))
        assertEquals(listOf(specifiers[3]), selected(SwiftTestPath.SwiftTesting("GreeterTests", listOf("GreeterSuite", "names(name:)")).filter))
        assertEquals(listOf(specifiers[6]), selected(SwiftTestPath.SwiftTesting("GreeterTests", listOf("topLevel()")).filter))
        assertEquals(specifiers.subList(0, 7), selected(SwiftTestPath.Target("GreeterTests").filter))
    }

    @Test fun `configuration scopes give the same filters`() {
        assertNull(SwiftTestConfiguration.filterOf(SwiftTestScope.ALL, "GreeterTests", null, null))
        assertEquals("^GreeterTests\\.", SwiftTestConfiguration.filterOf(SwiftTestScope.TARGET, "GreeterTests", null, null))
        assertEquals(listOf(specifiers[5]), selected(SwiftTestConfiguration.filterOf(SwiftTestScope.SUITE, "GreeterTests", "GreeterSuite/Inner", null)!!))
        assertEquals(listOf(specifiers[0]), selected(SwiftTestConfiguration.filterOf(SwiftTestScope.TEST, "GreeterTests", "GreeterXCTests", "testGreeting")!!))
        assertEquals(listOf(specifiers[6]), selected(SwiftTestConfiguration.filterOf(SwiftTestScope.TEST, "GreeterTests", null, "topLevel()")!!))
    }

    @Test fun `URLs round-trip and parameterized cases map to their function`() {
        val paths = listOf(
            SwiftTestPath.XCTest("M", "C"), SwiftTestPath.XCTest("M", "C", "testX"), SwiftTestPath.XCTest(null, "C", "testX"),
            SwiftTestPath.SwiftTesting("M", listOf("Outer", "Inner", "f(x:_:)")), SwiftTestPath.Target("M"),
        )
        for (path in paths) assertEquals(path, SwiftTestPath.parse(path.locationUrl))
        assertEquals(SwiftTestPath.SwiftTesting("M", listOf("S", "p(x:)")), SwiftTestPath.parse("swift-testing://M.S/p(x:)#case=\"a/b\""))
        assertEquals(SwiftTestPath.SwiftTesting("M", listOf("S", "f()")), SwiftTestPath.parseTestingId("M.S/f()/File.swift:3:4"))
        assertNull(SwiftTestPath.parse("swift-testing://NoModule"))
        assertNull(SwiftTestPath.parse("file:///x"))
    }

    @Test fun `rerun failed filters one test per failed node`() {
        assertEquals(
            listOf("^M\\.C/testX$", "^M\\.S/p\\(x:\\)(/|$)"),
            SwiftRerunFailedTestsAction.failedTestFilters(listOf(
                "swift-xctest://M/C/testX", "swift-testing://M.S/p(x:)#case=1", "swift-testing://M.S/p(x:)#case=2", "swift-target://M",
            )),
        )
    }

    @Test fun `event stream flags depend on the Swift version`() {
        val file = Path.of("/tmp/events.jsonl")
        val modern = listOf("--event-stream-output-path", file.toString(), "--event-stream-version", "0")
        assertEquals(modern, SwiftTestingEventStream.arguments(SwiftVersion(6, 4, 2), file))
        assertEquals(modern, SwiftTestingEventStream.arguments(SwiftVersion(6, 0), file))
        assertEquals(modern, SwiftTestingEventStream.arguments(null, file))
        assertEquals(
            listOf("--experimental-event-stream-output", file.toString(), "--experimental-event-stream-version", "0"),
            SwiftTestingEventStream.arguments(SwiftVersion.parseVersionOutput("Swift version 6.0-dev (LLVM x, Swift y)"), file),
        )
        assertTrue(SwiftTestingEventStream.arguments(SwiftVersion(5, 10), file).isEmpty())
    }

    @Test fun `events render as id-based service messages`() {
        val started = ServiceMessage.parse(
            SwiftTestEvent.TestStarted("n|1", "p", "it's [x]", "swift-xctest://M/C/testX", "/a.swift:1:2").toServiceMessage(),
        )!!
        assertEquals("testStarted", started.messageName)
        assertEquals(mapOf("name" to "it's [x]", "nodeId" to "n|1", "parentNodeId" to "p",
            "locationHint" to "swift-xctest://M/C/testX", "metainfo" to "/a.swift:1:2"), started.attributes)
        val failed = ServiceMessage.parse(SwiftTestEvent.TestFailed("n", "t", "boom\nline", "/a.swift:3: boom").toServiceMessage())!!
        assertEquals("boom\nline", failed.attributes["message"])
        val output = ServiceMessage.parse(SwiftTestEvent.RunOutput("hello\n", stderr = true).toServiceMessage())!!
        assertEquals("testStdErr", output.messageName)
        assertEquals(SwiftTestEvent.ROOT_ID, output.attributes["nodeId"])
        val finished = ServiceMessage.parse(SwiftTestEvent.TestFinished("n", "t", null).toServiceMessage())!!
        assertFalse("duration" in finished.attributes)
    }
}
