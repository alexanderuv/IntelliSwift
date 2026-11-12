package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.ProgressBuildEvent
import com.intellij.build.output.BuildOutputInstantReaderImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Parses `swift build` output captured from the fixture packages in
 * `src/test/testData/swiftpm/build-output/packages` (see `scripts/capture-build-output.sh`), with
 * the fixture directory replaced by `/FIXTURE`.
 */
class SwiftBuildOutputParserTest : BasePlatformTestCase() {
    private val buildId = Any()

    private fun sample(name: String): String = Path.of("src/test/testData/swiftpm/build-output/$name.txt").toFile().readText()

    private fun parse(output: String): List<BuildEvent> {
        val events = Collections.synchronizedList(ArrayList<BuildEvent>())
        val reader = BuildOutputInstantReaderImpl(buildId, buildId, { _, event -> events += event }, listOf(SwiftBuildOutputParser()))
        // The reader only parses complete lines.
        reader.append(if (output.endsWith("\n")) output else "$output\n")
        reader.closeAndGetFuture().get(30, TimeUnit.SECONDS)
        return events.toList()
    }

    private fun messages(output: String): List<MessageEvent> = parse(output).filterIsInstance<MessageEvent>()

    /** `KIND file:line:column title` (1-based, as the compiler prints it), or `KIND title` without a file. */
    private fun describe(event: MessageEvent): String = buildString {
        append(event.kind).append(' ')
        if (event is FileMessageEvent) {
            val position = event.filePosition
            append(position.path?.fileName).append(':').append(position.startLine + 1).append(':').append(position.startColumn + 1).append(' ')
        }
        append(event.message)
    }

    private val brokenDiagnostics = listOf(
        "WARNING Broken.swift:5:13 initialization of immutable value 'unused' was never used; consider replacing with assignment to '_' or removing it",
        "ERROR Broken.swift:10:28 cannot convert value of type 'Int' to specified type 'String'",
        "ERROR Broken.swift:11:33 missing argument for parameter 'suffix' in call",
        "INFO Broken.swift:4:17 'label(prefix:suffix:)' declared here",
        "ERROR Broken.swift:11:16 cannot convert return expression of type 'String' to return type 'Int'",
    )

    private val nativeDeprecation =
        "WARNING '--build-system native' has been deprecated and will be removed in a future release; please report an issue at " +
            "https://github.com/swiftlang/swift-package-manager/issues if you are unable to adopt the default build system."

    fun `test diagnostics with excerpts become file messages`() {
        val messages = messages(sample("compile-errors-nocolor"))
        assertEquals(brokenDiagnostics, messages.map(::describe))

        val missingArgument = messages[2] as FileMessageEvent
        assertEquals(Path.of("/FIXTURE/build-output/packages/Broken/Sources/Broken/Broken.swift"), missingArgument.filePosition.path)
        assertEquals(SwiftBuildOutputParser.GROUP_COMPILER, missingArgument.group)
        // The excerpt, with the note marker, is the description; the task's command line is not.
        val description = missingArgument.description!!
        assertTrue(description, description.startsWith("/FIXTURE/build-output/packages/Broken/Sources/Broken/Broken.swift:11:33: error: missing argument"))
        assertTrue(description, "11 |         return label(prefix: \"n\")" in description)
        assertTrue(description, "`- note: 'label(prefix:suffix:)' declared here" in description)
        assertFalse(description, "swift-frontend" in description)
    }

    fun `test the warning keeps its diagnostic group in the description`() {
        val warning = messages(sample("compile-errors-nocolor")).first()
        assertFalse(warning.message.contains("[#NoUsage]"))
        assertTrue(warning.description!!.contains("removing it [#NoUsage]"))
    }

    fun `test colored output parses like plain output`() {
        val colored = messages(sample("compile-errors"))
        assertEquals(brokenDiagnostics, colored.map(::describe))
        assertTrue(colored.none { '\u001B' in it.message || '\u001B' in it.description.orEmpty() })
    }

    fun `test native build system output`() {
        assertEquals(brokenDiagnostics + nativeDeprecation, messages(sample("compile-errors-native")).map(::describe))
    }

    fun `test LLVM style diagnostics with caret lines, fix-its and a note header`() {
        val messages = messages(sample("compile-errors-llvm"))
        assertEquals(brokenDiagnostics, messages.map(::describe))
        val missingArgument = messages[2].description!!
        assertTrue(missingArgument, ", suffix: <#String#>" in missingArgument)
        // The note on its own header line is folded into the error's description.
        assertTrue(missingArgument, "Broken.swift:4:17: note: 'label(prefix:suffix:)' declared here" in missingArgument)
        assertTrue(missingArgument, "    public func label(prefix: String, suffix: String) -> String {" in missingArgument)
    }

    fun `test a note in a synthesized buffer is attached to its error`() {
        val messages = messages(sample("cross-module-note"))
        assertEquals(
            listOf(
                "ERROR main.swift:3:15 'Counter' initializer is inaccessible due to 'internal' protection level",
                "INFO 'init()' declared here",
            ),
            messages.map(::describe),
        )
        val description = messages[0].description!!
        assertTrue(description, "Broken.Counter.init:2:10: note: 'init()' declared here" in description)
        assertTrue(description, "2 | internal init()}" in description)
    }

    fun `test clang diagnostics`() {
        val messages = messages(sample("clang-error"))
        assertEquals(listOf("ERROR clib.c:5:20 use of undeclared identifier 'undeclared_value'"), messages.map(::describe))
        assertTrue(messages[0].description!!.contains("^~~~~~~~~~~~~~~~"))
    }

    fun `test linker errors`() {
        val messages = messages(sample("link-error"))
        assertEquals(
            listOf("ERROR Undefined symbol: _intelliswift_missing_symbol", "ERROR Linker command failed with exit code 1"),
            messages.map(::describe),
        )
        assertTrue(messages.all { it.group == SwiftBuildOutputParser.GROUP_LINKER })
        val undefined = messages[0].description!!
        assertTrue(undefined, "_main in main.o" in undefined)
        assertTrue(undefined, "ld: symbol(s) not found for architecture arm64" in undefined)
    }

    fun `test linker errors of the native build system`() {
        assertEquals(
            listOf(
                "ERROR Linker command failed with exit code 1",
                "ERROR Undefined symbol: _intelliswift_missing_symbol",
                nativeDeprecation,
            ),
            messages(sample("link-error-native")).map(::describe),
        )
    }

    fun `test GNU ld undefined references are navigable`() {
        // Not captured: the format of GNU ld on Linux, which this machine does not have.
        val output = """
            /usr/bin/ld: /work/App/.build/x86_64-unknown-linux-gnu/debug/App.build/main.swift.o: in function `App_main':
            /work/App/Sources/App/main.swift:4: undefined reference to `missing_symbol'
            clang: error: linker command failed with exit code 1 (use -v to see invocation)
            /usr/bin/ld: cannot find -lmissing: No such file or directory
            ld: warning: object file was built for a newer version
        """.trimIndent()
        assertEquals(
            listOf(
                "ERROR main.swift:4:1 Undefined reference to missing_symbol",
                "ERROR Linker command failed with exit code 1",
                "ERROR /usr/bin/ld: cannot find -lmissing: No such file or directory",
                "WARNING ld: warning: object file was built for a newer version",
            ),
            messages(output).map(::describe),
        )
    }

    fun `test manifest errors`() {
        val messages = messages(sample("manifest-error"))
        assertEquals(
            listOf(
                "ERROR 'badmanifest': Invalid manifest",
                "ERROR Package.swift:8:53 cannot find 'undefinedDependency' in scope",
            ),
            messages.map(::describe),
        )
        assertEquals(SwiftBuildOutputParser.GROUP_SWIFTPM, messages[0].group)
        assertTrue(messages[0].description!!.contains("(compiled with: "))
    }

    fun `test repeated SwiftPM warnings are reported once`() {
        assertEquals(
            listOf(
                "WARNING 'missingtarget': Source files for target Nowhere should be located under 'Sources/Nowhere', 'Sources', " +
                    "or a custom sources path can be set with the 'path' property in Package.swift",
            ),
            messages(sample("manifest-warnings")).map(::describe),
        )
    }

    fun `test progress lines become progress events`() {
        val events = parse(sample("success"))
        assertTrue(events.none { it is MessageEvent })
        val progress = events.filterIsInstance<ProgressBuildEvent>()
        assertTrue(progress.all { it.id === buildId })
        assertEquals("Building for debugging...", progress.first().message)
        val preplanning = progress.first { it.message == "Pre-planning" }
        assertEquals(1L to 212L, preplanning.progress to preplanning.total)
        // `[60 / 93] Greeter`, with thin spaces around the slash.
        val numbered = progress.first { it.message == "Greeter" }
        assertEquals(60L to 93L, numbered.progress to numbered.total)
        assertTrue(progress.any { it.message == "Planning deferred tasks" && it.total < 0 })
        val complete = progress.last()
        assertTrue(complete.message, complete.message.startsWith("Build complete!"))
        assertEquals(166L to 166L, complete.progress to complete.total)
    }

    fun `test progress lines of the native build system`() {
        val events = parse(sample("success-native"))
        assertEquals(listOf(nativeDeprecation), events.filterIsInstance<MessageEvent>().map(::describe))
        val compiling = events.filterIsInstance<ProgressBuildEvent>().first { it.message == "Compiling CHelpers helpers.c" }
        assertEquals(5L to 10L, compiling.progress to compiling.total)
    }

    fun `test a failed build with nothing else to say reports why`() {
        // swift-build's task summary, without diagnostics (as when the compiler crashes).
        val crash = """
            error: SwiftCompile normal arm64 /work/App/Sources/App/main.swift failed with a nonzero exit code. Command line:     cd /work/App
                builtin-SwiftPerFileCompile main.swift
            error: Build failed
        """.trimIndent()
        assertEquals(
            listOf("ERROR SwiftCompile normal arm64 /work/App/Sources/App/main.swift failed with a nonzero exit code"),
            messages(crash).map(::describe),
        )
        assertEquals(listOf("ERROR Build failed"), messages("error: fatalError").map(::describe))
    }

    fun `test summaries are not repeated after diagnostics`() {
        val output = """
            /work/App/Sources/App/main.swift:1:1: error: expressions are not allowed at the top level
            error: fatalError
        """.trimIndent()
        assertEquals(listOf("ERROR main.swift:1:1 expressions are not allowed at the top level"), messages(output).map(::describe))
    }

    fun `test diagnostics without a column or a file`() {
        val output = """
            /work/App/Sources/App/main.swift:7: warning: something at a line
            <unknown>:0: error: unable to load standard library for target 'arm64-apple-macosx13.0'
        """.trimIndent()
        assertEquals(
            listOf(
                "WARNING main.swift:7:1 something at a line",
                "ERROR unable to load standard library for target 'arm64-apple-macosx13.0'",
            ),
            messages(output).map(::describe),
        )
    }
}
