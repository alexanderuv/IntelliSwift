package io.github.alexanderuv.intelliswift.swiftpm.execution

import io.github.alexanderuv.intelliswift.swiftpm.execution.TestEventRendering.render
import io.github.alexanderuv.intelliswift.swiftpm.execution.TestEventRendering.sample
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestEvent
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestTree
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.XCTestOutputParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XCTest console output to test tree events, on output of the Greeter fixture's tests:
 * `greeter-macos.stdout.txt` was captured with swift 6.4.2 on macOS; `greeter-linux.stdout.txt`
 * has the same run in swift-corelibs-xctest's format (its `PrintObserver`).
 */
class XCTestOutputParserTest {

    private fun parse(output: String, moduleOf: (String) -> String? = { null }): String {
        val events = ArrayList<SwiftTestEvent>()
        val tree = SwiftTestTree { events += it }
        val parser = XCTestOutputParser(tree, moduleOf)
        for (line in output.lines()) {
            if (!parser.line(line)) events += SwiftTestEvent.RunOutput(line, false)
        }
        parser.finish()
        tree.finishAll(SwiftTestTree.Failure("interrupted", ""))
        return render(events.filterNot { it is SwiftTestEvent.RunOutput && (it.text.isBlank() || !it.text.startsWith("Test Suite")) })
    }

    @Test fun `macOS output gives classes, results, durations and failure locations`() {
        assertEquals("""
            run-out 'Test Suite 'All tests' started at 2026-11-11 20:46:31.432.'
            run-out 'Test Suite 'GreeterTests.xctest' started at 2026-11-11 20:46:31.434.'
            run-out 'Test Suite 'GreeterXCTests' started at 2026-11-11 20:46:31.434.'
            suite+ target:GreeterTests <- 0 'GreeterTests' swift-target://GreeterTests
            suite+ xctest:GreeterTests/GreeterXCTests <- target:GreeterTests 'GreeterXCTests' swift-xctest://GreeterTests/GreeterXCTests
            test+ xctest:GreeterTests/GreeterXCTests/testFailing <- xctest:GreeterTests/GreeterXCTests 'testFailing' swift-xctest://GreeterTests/GreeterXCTests/testFailing
            failed xctest:GreeterTests/GreeterXCTests/testFailing 'XCTAssertEqual failed: ("HELLO, ADA!") is not equal to ("nope")' [/FIXTURE/Greeter/Tests/GreeterTests/GreeterXCTests.swift:10: XCTAssertEqual failed: ("HELLO, ADA!") is not equal to ("nope")]
            test- xctest:GreeterTests/GreeterXCTests/testFailing 108ms
            test+ xctest:GreeterTests/GreeterXCTests/testGreeting <- xctest:GreeterTests/GreeterXCTests 'testGreeting' swift-xctest://GreeterTests/GreeterXCTests/testGreeting
            test- xctest:GreeterTests/GreeterXCTests/testGreeting 0ms
            test+ xctest:GreeterTests/GreeterXCTests/testSkipped <- xctest:GreeterTests/GreeterXCTests 'testSkipped' swift-xctest://GreeterTests/GreeterXCTests/testSkipped
            ignored xctest:GreeterTests/GreeterXCTests/testSkipped 'Not ready'
            test- xctest:GreeterTests/GreeterXCTests/testSkipped 1ms
            suite- xctest:GreeterTests/GreeterXCTests
            run-out 'Test Suite 'GreeterXCTests' failed at 2026-11-11 20:46:31.543.'
            run-out 'Test Suite 'GreeterTests.xctest' failed at 2026-11-11 20:46:31.543.'
            run-out 'Test Suite 'All tests' failed at 2026-11-11 20:46:31.543.'
            suite- target:GreeterTests
        """.trimIndent(), parse(sample("greeter-macos.stdout.txt")))
    }

    @Test fun `Linux output names no module, which is resolved from the class`() {
        assertEquals("""
            run-out 'Test Suite 'All tests' started at 2026-11-11 20:46:31.432'
            run-out 'Test Suite 'GreeterPackageTests.xctest' started at 2026-11-11 20:46:31.434'
            run-out 'Test Suite 'GreeterXCTests' started at 2026-11-11 20:46:31.434'
            suite+ target:GreeterTests <- 0 'GreeterTests' swift-target://GreeterTests
            suite+ xctest:GreeterTests/GreeterXCTests <- target:GreeterTests 'GreeterXCTests' swift-xctest://GreeterTests/GreeterXCTests
            test+ xctest:GreeterTests/GreeterXCTests/testFailing <- xctest:GreeterTests/GreeterXCTests 'testFailing' swift-xctest://GreeterTests/GreeterXCTests/testFailing
            failed xctest:GreeterTests/GreeterXCTests/testFailing 'XCTAssertEqual failed: ("HELLO, ADA!") is not equal to ("nope")' [/FIXTURE/Greeter/Tests/GreeterTests/GreeterXCTests.swift:10: XCTAssertEqual failed: ("HELLO, ADA!") is not equal to ("nope")]
            test- xctest:GreeterTests/GreeterXCTests/testFailing 108ms
            test+ xctest:GreeterTests/GreeterXCTests/testGreeting <- xctest:GreeterTests/GreeterXCTests 'testGreeting' swift-xctest://GreeterTests/GreeterXCTests/testGreeting
            out xctest:GreeterTests/GreeterXCTests/testGreeting 'Greeting Ada'
            test- xctest:GreeterTests/GreeterXCTests/testGreeting 0ms
            test+ xctest:GreeterTests/GreeterXCTests/testSkipped <- xctest:GreeterTests/GreeterXCTests 'testSkipped' swift-xctest://GreeterTests/GreeterXCTests/testSkipped
            ignored xctest:GreeterTests/GreeterXCTests/testSkipped 'Not ready'
            test- xctest:GreeterTests/GreeterXCTests/testSkipped 1ms
            suite- xctest:GreeterTests/GreeterXCTests
            run-out 'Test Suite 'GreeterXCTests' failed at 2026-11-11 20:46:31.543'
            run-out 'Test Suite 'GreeterPackageTests.xctest' failed at 2026-11-11 20:46:31.543'
            run-out 'Test Suite 'All tests' failed at 2026-11-11 20:46:31.543'
            suite- target:GreeterTests
        """.trimIndent(), parse(sample("greeter-linux.stdout.txt")) { if (it == "GreeterXCTests") "GreeterTests" else null })
    }

    @Test fun `Linux classes of an unknown module are top-level nodes`() {
        val events = parse("""
            Test Case 'GreeterXCTests.testGreeting' started at 2026-11-11 20:46:31.542
            Test Case 'GreeterXCTests.testGreeting' passed (0.002 seconds)
        """.trimIndent())
        assertEquals("""
            suite+ xctest:/GreeterXCTests <- 0 'GreeterXCTests' swift-xctest:///GreeterXCTests
            test+ xctest:/GreeterXCTests/testGreeting <- xctest:/GreeterXCTests 'testGreeting' swift-xctest:///GreeterXCTests/testGreeting
            test- xctest:/GreeterXCTests/testGreeting 2ms
            suite- xctest:/GreeterXCTests
        """.trimIndent(), events)
    }

    @Test fun `Linux classes of the same name in two targets get a node each`() {
        // corelibs XCTest names no module, and both ATests and BTests declare UtilsTests.
        val events = parse("""
            Test Suite 'UtilsTests' started at 2026-11-11 20:46:31.432
            Test Case 'UtilsTests.testExample' started at 2026-11-11 20:46:31.432
            Test Case 'UtilsTests.testExample' passed (0.001 seconds)
            Test Suite 'UtilsTests' passed at 2026-11-11 20:46:31.433
            Test Suite 'UtilsTests' started at 2026-11-11 20:46:31.433
            Test Case 'UtilsTests.testExample' started at 2026-11-11 20:46:31.433
            /src/Tests/BTests/UtilsTests.swift:5: error: UtilsTests.testExample : XCTAssertTrue failed
            Test Case 'UtilsTests.testExample' failed (0.002 seconds)
            Test Suite 'UtilsTests' failed at 2026-11-11 20:46:31.435
        """.trimIndent())
        assertEquals("""
            run-out 'Test Suite 'UtilsTests' started at 2026-11-11 20:46:31.432'
            suite+ xctest:/UtilsTests <- 0 'UtilsTests' swift-xctest:///UtilsTests
            test+ xctest:/UtilsTests/testExample <- xctest:/UtilsTests 'testExample' swift-xctest:///UtilsTests/testExample
            test- xctest:/UtilsTests/testExample 1ms
            suite- xctest:/UtilsTests
            run-out 'Test Suite 'UtilsTests' passed at 2026-11-11 20:46:31.433'
            run-out 'Test Suite 'UtilsTests' started at 2026-11-11 20:46:31.433'
            suite+ xctest:/UtilsTests#2 <- 0 'UtilsTests' swift-xctest:///UtilsTests
            test+ xctest:/UtilsTests#2/testExample <- xctest:/UtilsTests#2 'testExample' swift-xctest:///UtilsTests/testExample
            failed xctest:/UtilsTests#2/testExample 'XCTAssertTrue failed' [/src/Tests/BTests/UtilsTests.swift:5: XCTAssertTrue failed]
            test- xctest:/UtilsTests#2/testExample 2ms
            suite- xctest:/UtilsTests#2
            run-out 'Test Suite 'UtilsTests' failed at 2026-11-11 20:46:31.435'
        """.trimIndent(), events)
    }

    @Test fun `a test the process exited during fails with the failures it recorded`() {
        val events = parse("""
            Test Case '-[M.C testCrash]' started.
            /src/C.swift:3: error: -[M.C testCrash] : first
        """.trimIndent())
        assertEquals("""
            suite+ target:M <- 0 'M' swift-target://M
            suite+ xctest:M/C <- target:M 'C' swift-xctest://M/C
            test+ xctest:M/C/testCrash <- xctest:M/C 'testCrash' swift-xctest://M/C/testCrash
            failed xctest:M/C/testCrash 'first (and 1 more)' [/src/C.swift:3: first | The test did not finish: the test process exited while it was running]
            test- xctest:M/C/testCrash -
            suite- xctest:M/C
            suite- target:M
        """.trimIndent(), events)
    }

    @Test fun `several failures of one test are combined`() {
        val events = parse("""
            Test Case '-[M.C testTwo]' started.
            C:\src\C.swift:3: error: -[M.C testTwo] : first
            C:\src\C.swift:4: error: -[M.C testTwo] : second
            Test Case '-[M.C testTwo]' failed (1.5 seconds).
        """.trimIndent())
        assertEquals(
            """failed xctest:M/C/testTwo 'first (and 1 more)' [C:\src\C.swift:3: first | C:\src\C.swift:4: second]""",
            events.lines().single { it.startsWith("failed") },
        )
        assertEquals("test- xctest:M/C/testTwo 1500ms", events.lines().single { it.startsWith("test-") })
    }
}
