package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.EventStreamTailer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections

/** Following a file another process appends to (runs on the platform's scheduled executor). */
class EventStreamTailerTest : BasePlatformTestCase() {

    fun `test lines are passed on as they are completed, including a last line without a break`() {
        val dir = Files.createTempDirectory("tailer")
        val file = dir.resolve("events.jsonl")
        val lines = Collections.synchronizedList(ArrayList<String>())
        val tailer = EventStreamTailer(file, { lines += it }, pollIntervalMs = 5)
        try {
            tailer.start()
            Thread.sleep(30) // the file does not exist yet
            Files.write(file, "{\"a\":1}\n{\"b\":".toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            waitFor { lines.size == 1 }
            assertEquals(listOf("{\"a\":1}"), lines.toList())

            // A multi-byte character split across writes is decoded whole.
            val arrow = "→".toByteArray()
            Files.write(file, "2, \"c\":\"".toByteArray() + arrow.copyOfRange(0, 1), StandardOpenOption.APPEND)
            Thread.sleep(30)
            Files.write(file, arrow.copyOfRange(1, arrow.size) + "\"}\r\n\n{\"last\":true}".toByteArray(), StandardOpenOption.APPEND)
            waitFor { lines.size == 2 }
            tailer.stopAndDrain()
            assertEquals(listOf("{\"a\":1}", "{\"b\":2, \"c\":\"→\"}", "{\"last\":true}"), lines.toList())
        } finally {
            tailer.stop()
            dir.toFile().deleteRecursively()
        }
    }

    fun `test draining a file that never appeared gives nothing`() {
        val lines = ArrayList<String>()
        val tailer = EventStreamTailer(Files.createTempDirectory("tailer").resolve("missing.jsonl"), { lines += it })
        tailer.start()
        tailer.stopAndDrain()
        assertEmpty(lines)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out")
            Thread.sleep(5)
        }
    }
}
