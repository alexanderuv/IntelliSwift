package io.github.alexanderuv.intelliswift.swiftpm

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.SystemInfo
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** How SwiftCommandRunner hands output on and stops commands; runs `/bin/sh`. */
class SwiftCommandRunnerTest {

    @Test fun `output arrives in whole lines per stream, one call at a time`() {
        assumeFalse(SystemInfo.isWindows)
        // Each stream writes its lines in pieces, interleaved with the other stream's.
        val script = (1..200).joinToString("; ") { i -> "printf 'out-$i-'; printf 'err-$i-' >&2; printf 'end\\n'; printf 'end\\n' >&2" } +
            "; printf 'last'"
        val lines = Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val inside = AtomicInteger()
        val overlaps = AtomicInteger()
        val output = SwiftCommandRunner.run(GeneralCommandLine("/bin/sh", "-c", script)) { text, type ->
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            when {
                ProcessOutputType.isStdout(type) -> lines += "out" to text
                ProcessOutputType.isStderr(type) -> lines += "err" to text
            }
            inside.decrementAndGet()
        }
        assertEquals(0, output.exitCode)
        assertEquals("the consumer is never called concurrently", 0, overlaps.get())
        val out = lines.filter { it.first == "out" }.map { it.second }
        val err = lines.filter { it.first == "err" }.map { it.second }
        assertEquals((1..200).map { "out-$it-end\n" } + "last", out)
        assertEquals((1..200).map { "err-$it-end\n" }, err)
    }

    @Test fun `a cancelled command is not started`() {
        assumeFalse(SystemInfo.isWindows)
        val started = AtomicInteger()
        val error = runCatching {
            SwiftCommandRunner.run(GeneralCommandLine("/bin/sh", "-c", "echo started"), cancelled = { true }) { _, _ -> started.incrementAndGet() }
        }.exceptionOrNull()
        assertTrue("$error", error is ProcessCanceledException)
        assertEquals(0, started.get())
    }
}
