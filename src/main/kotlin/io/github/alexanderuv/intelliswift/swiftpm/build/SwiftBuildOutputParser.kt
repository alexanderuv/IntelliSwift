package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.ProgressBuildEvent
import com.intellij.build.output.BuildOutputInstantReader
import com.intellij.build.output.BuildOutputParser
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Turns `swift build` output into Build tool window events, for both of SwiftPM's build systems
 * (the default swift-build one and `--build-system native`).
 *
 * - Compiler diagnostics (`path:line:col: error|warning|note: message`, from swiftc and clang)
 *   become navigable file messages. The source excerpt Swift 6 prints after a diagnostic (the
 *   `|` gutter lines), or the LLVM-style code and caret lines, become its description. Notes,
 *   inline (`` `- note: ``) or on their own header line, are folded into that description and also
 *   reported as INFO messages at their own location: the Build tree cannot nest a message under a
 *   file message.
 * - Linker errors (`Undefined symbols ...`, `ld: ...`, `error: link command failed ...`) and
 *   SwiftPM's own `error:`/`warning:` lines (manifest errors, for example) become messages.
 * - Progress lines (`[12/40] Compiling Greeter Greeter.swift`, `[18 / 26] Broken`,
 *   `Building for debugging...`, `Build complete!`) become progress events.
 * - SwiftPM's closing `error: Build failed` / `error: fatalError` and swift-build's
 *   `... failed with a nonzero exit code` task summaries only become messages when no error was
 *   reported otherwise, so a failed build always shows at least one error.
 *
 * Use one instance per build: it remembers what it reported, to drop the diagnostics SwiftPM
 * repeats (swift-build prints a diagnostic once per compile step that hits it).
 */
class SwiftBuildOutputParser : BuildOutputParser {
    private val reported = HashSet<String>()
    private var errorCount = 0
    private val unreportedTaskFailures = ArrayList<String>()
    private var lastTotal = 0L

    override fun parse(line: String, reader: BuildOutputInstantReader, messageConsumer: Consumer<in BuildEvent>): Boolean {
        val text = withoutTaskPrefix(SwiftOutputText.clean(line))
        if (text.isBlank()) return false
        val out = Emitter(reader.parentEventId, messageConsumer)
        return parseProgress(text, out) ||
            parseNoise(text, reader) ||
            parseLinker(text, reader, out) ||
            parseSwiftPm(text, reader, out) ||
            parseDiagnostic(text, reader, out)
    }

    // region Progress

    private fun parseProgress(text: String, out: Emitter): Boolean {
        PROGRESS.matchEntire(text)?.let { m ->
            val (label, done, total, description) = m.destructured
            val progress = done.toLong()
            lastTotal = total.toLong()
            out.progress(description.ifBlank { label.ifBlank { "Building" } }, progress, lastTotal)
            return true
        }
        STATUS.matchEntire(text)?.let { m ->
            out.progress(m.groupValues[1], null, null)
            return true
        }
        if (BUILD_COMPLETE.matches(text)) {
            out.progress(text, lastTotal.takeIf { it > 0 }, lastTotal.takeIf { it > 0 })
            return true
        }
        if (BUILDING_FOR.matches(text)) {
            out.progress(text, null, null)
            return true
        }
        return false
    }

    // endregion

    /** Lines that carry nothing beyond what diagnostics already say. */
    private fun parseNoise(text: String, reader: BuildOutputInstantReader): Boolean {
        if (FOOTNOTE.matches(text)) return true
        if (text == "Failed frontend command:") {
            reader.readLine() // The frontend command line.
            return true
        }
        return false
    }

    // region Linker

    private fun parseLinker(text: String, reader: BuildOutputInstantReader, out: Emitter): Boolean {
        UNDEFINED_SYMBOLS.matchEntire(text)?.let { m ->
            val block = arrayListOf(text)
            val symbols = ArrayList<String>()
            while (true) {
                val next = reader.readLine()?.let(SwiftOutputText::clean) ?: break
                if (next.isNotEmpty() && next[0].isWhitespace()) {
                    block += next
                    UNDEFINED_SYMBOL.matchEntire(next)?.let { symbols += it.groupValues[1] }
                    continue
                }
                if (SYMBOLS_NOT_FOUND.matches(next)) block += next else reader.pushBack()
                break
            }
            val title = when (symbols.size) {
                0 -> "Undefined symbols for architecture ${m.groupValues[1]}"
                1 -> "Undefined symbol: ${symbols[0]}"
                else -> "Undefined symbols: " + symbols.take(3).joinToString(", ") + if (symbols.size > 3) ", …" else ""
            }
            out.message(MessageEvent.Kind.ERROR, title, block.joinToString("\n"), GROUP_LINKER)
            return true
        }
        LINK_FAILED.matchEntire(text)?.let { m ->
            out.message(MessageEvent.Kind.ERROR, "Linker command failed with exit code ${m.groupValues[1]}", text, GROUP_LINKER)
            return true
        }
        LD_CONTEXT.matchEntire(text)?.let {
            // `ld: main.o: in function 'main':` introduces the undefined references that follow.
            val next = reader.readLine()?.let(SwiftOutputText::clean)
            val reference = next?.let(UNDEFINED_REFERENCE::matchEntire)
            if (reference == null) {
                if (next != null) reader.pushBack()
                return true
            }
            undefinedReference(reference, "$text\n$next", out)
            return true
        }
        UNDEFINED_REFERENCE.matchEntire(text)?.let { m ->
            undefinedReference(m, text, out)
            return true
        }
        LD.matchEntire(text)?.let { m ->
            val kind = if (m.groupValues[1].startsWith("warning:")) MessageEvent.Kind.WARNING else MessageEvent.Kind.ERROR
            out.message(kind, text, text, GROUP_LINKER)
            return true
        }
        return false
    }

    private fun undefinedReference(m: MatchResult, description: String, out: Emitter) {
        val (path, line, symbol) = m.destructured
        val position = Location(path, line.toIntOrNull(), null).filePosition()
        out.message(MessageEvent.Kind.ERROR, "Undefined reference to $symbol", description, GROUP_LINKER, position)
    }

    // endregion

    // region SwiftPM

    private fun parseSwiftPm(text: String, reader: BuildOutputInstantReader, out: Emitter): Boolean {
        if (BUILD_FAILED.matches(text)) {
            if (errorCount == 0) {
                if (unreportedTaskFailures.isEmpty()) out.message(MessageEvent.Kind.ERROR, "Build failed", text, GROUP_SWIFTPM)
                for (failure in unreportedTaskFailures) out.message(MessageEvent.Kind.ERROR, failure, failure, GROUP_SWIFTPM)
            }
            unreportedTaskFailures.clear()
            return true
        }
        TASK_FAILED.matchEntire(text)?.let { m ->
            // swift-build follows this with the indented command line; the diagnostics come separately.
            while (true) {
                val next = reader.readLine()?.let(SwiftOutputText::clean) ?: break
                if (next.isEmpty() || !next[0].isWhitespace()) {
                    reader.pushBack()
                    break
                }
            }
            unreportedTaskFailures += "${m.groupValues[1]} failed with a nonzero exit code"
            return true
        }
        SWIFTPM_MESSAGE.matchEntire(text)?.let { m ->
            val (tool, severity, message) = m.destructured
            val title = message.substringBefore(" (compiled with: ").let { if (tool.isEmpty()) it else "$tool: $it" }
            out.message(kindOf(severity), title, text, GROUP_SWIFTPM)
            return true
        }
        return false
    }

    // endregion

    // region Compiler diagnostics

    private fun parseDiagnostic(text: String, reader: BuildOutputInstantReader, out: Emitter): Boolean {
        val diagnostic = header(text) ?: return false
        readExcerpt(diagnostic, reader)
        if (!diagnostic.isNote) readFollowingNotes(diagnostic, reader)
        emit(diagnostic, out)
        return true
    }

    private fun header(text: String): Diagnostic? {
        val m = HEADER.matchEntire(text) ?: return null
        val (path, line, column, severity, message) = m.destructured
        return Diagnostic(kindOf(severity), severity, Location(path, line.toInt(), column.toIntOrNull()), message, text)
    }

    /**
     * Reads the source excerpt after a diagnostic header: Swift 6's `|` gutter lines (clang uses
     * the same gutter), or the LLVM style's code line followed by a caret line.
     */
    private fun readExcerpt(diagnostic: Diagnostic, reader: BuildOutputInstantReader) {
        var sourceLine: Int? = null
        var sawGutter = false
        while (true) {
            val raw = reader.readLine() ?: return
            val text = SwiftOutputText.clean(raw)
            val source = SOURCE_LINE.matchEntire(text)
            val marker = if (source == null) MARKER_LINE.matchEntire(text) else null
            when {
                source != null -> {
                    sourceLine = source.groupValues[1].toInt()
                    sawGutter = true
                }
                marker != null -> {
                    sawGutter = true
                    inlineDiagnostic(marker.groupValues[1], sourceLine, diagnostic)
                }
                sawGutter && ELISION.matches(text) -> {}
                BOX_LINE.matches(text) -> {}
                !sawGutter && diagnostic.lines.size == 1 -> {
                    // LLVM style: the code line, a caret line (`~~~~^~~~`), then indented fix-its.
                    val caret = reader.readLine()
                    if (caret == null || !CARET_LINE.matches(SwiftOutputText.clean(caret))) {
                        reader.pushBack(if (caret != null) 2 else 1)
                        return
                    }
                    diagnostic.lines += text
                    diagnostic.lines += SwiftOutputText.clean(caret)
                    while (true) {
                        val fixIt = reader.readLine()?.let(SwiftOutputText::clean) ?: return
                        if (fixIt.isBlank() || !fixIt[0].isWhitespace()) {
                            reader.pushBack()
                            return
                        }
                        diagnostic.lines += fixIt
                    }
                }
                else -> {
                    reader.pushBack()
                    return
                }
            }
            diagnostic.lines += text
        }
    }

    /** An `` `- note: ...`` marker under a source line becomes a note of [diagnostic] at that place. */
    private fun inlineDiagnostic(content: String, sourceLine: Int?, diagnostic: Diagnostic) {
        val m = INLINE_MARKER.matchEntire(content) ?: return
        val (indent, severity, message) = m.destructured
        val kind = kindOf(severity)
        if (kind == diagnostic.kind && title(message) == diagnostic.title) return
        val location = Location(diagnostic.location.path, sourceLine ?: diagnostic.location.line, indent.length)
        diagnostic.notes += Diagnostic(kind, severity, location, message, content.trim(), isInline = true)
    }

    /** Notes printed as diagnostics of their own (in another file, or in LLVM style) after [diagnostic]. */
    private fun readFollowingNotes(diagnostic: Diagnostic, reader: BuildOutputInstantReader) {
        var unconsumed = 0
        while (true) {
            val raw = reader.readLine() ?: break
            unconsumed++
            val text = withoutTaskPrefix(SwiftOutputText.clean(raw))
            if (text.isBlank()) continue
            val note = header(text)?.takeIf { it.isNote } ?: break
            unconsumed = 0
            readExcerpt(note, reader)
            diagnostic.notes += note
        }
        if (unconsumed > 0) reader.pushBack(unconsumed)
    }

    private fun emit(diagnostic: Diagnostic, out: Emitter) {
        val description = buildString {
            diagnostic.lines.joinTo(this, "\n")
            // Inline notes are already part of the excerpt.
            for (note in diagnostic.notes.filterNot { it.isInline }) {
                append("\n\n")
                note.lines.joinTo(this, "\n")
            }
        }
        if (!out.message(diagnostic.kind, diagnostic.title, description, GROUP_COMPILER, diagnostic.location.filePosition(), diagnostic.location)) return
        for (note in diagnostic.notes) {
            out.message(note.kind, note.title, description, GROUP_COMPILER, note.location.filePosition(), note.location)
        }
    }

    // endregion

    private class Location(val path: String, val line: Int?, val column: Int?) {
        /** Where to navigate, for a real file; compilers also report synthesized buffers such as `Module.Type.init`. */
        fun filePosition(): FilePosition? {
            val file = try { Path.of(path) } catch (_: InvalidPathException) { null } ?: return null
            if (!file.isAbsolute) return null
            return FilePosition(file, ((line ?: 1) - 1).coerceAtLeast(0), ((column ?: 1) - 1).coerceAtLeast(0))
        }

        override fun toString(): String = "$path:${line ?: ""}:${column ?: ""}"
    }

    /** A compiler diagnostic; [isInline] for one printed as a marker in another's excerpt. */
    private class Diagnostic(
        val kind: MessageEvent.Kind,
        severity: String,
        val location: Location,
        message: String,
        header: String,
        val isInline: Boolean = false,
    ) {
        val title = title(message)
        val isNote = severity == "note" || severity == "remark"
        val lines = arrayListOf(header)
        val notes = ArrayList<Diagnostic>()
    }

    private inner class Emitter(private val parentId: Any, private val consumer: Consumer<in BuildEvent>) {
        /** Reports a message unless the same one was reported before; returns whether it was reported. */
        fun message(
            kind: MessageEvent.Kind,
            title: String,
            description: String,
            group: String,
            position: FilePosition? = null,
            location: Location? = null,
        ): Boolean {
            if (!reported.add("$kind|$title|${location ?: position?.let { "${it.path}:${it.startLine}:${it.startColumn}" }}")) return false
            if (kind == MessageEvent.Kind.ERROR) errorCount++
            val builder = MessageEvent.builder(title, kind).withParentId(parentId).withGroup(group).withDescription(description)
            if (position != null) builder.withFilePosition(position)
            consumer.accept(builder.build())
            return true
        }

        fun progress(message: String, progress: Long?, total: Long?) {
            val builder = ProgressBuildEvent.builder(parentId, message)
            if (progress != null && total != null) builder.withProgress(progress).withTotal(total).withUnit("tasks")
            consumer.accept(builder.build())
        }
    }

    companion object {
        const val GROUP_COMPILER: String = "Compiler"
        const val GROUP_LINKER: String = "Linker"
        const val GROUP_SWIFTPM: String = "SwiftPM"

        /** Horizontal space as SwiftPM prints it: swift-build writes `[18 / 26]` with thin spaces. */
        private const val SP = """[\s\p{Z}]"""

        private val PROGRESS = Regex("""^\[(?:([^\]\d][^\]]*?)$SP+)?(\d+)$SP*/$SP*(\d+)\](?:$SP+(.*))?$""")
        private val STATUS = Regex("""^\[([A-Za-z][^\]]*)\]$""")
        private val BUILD_COMPLETE = Regex("""^Build (?:of .+ )?complete!.*$""")
        private val BUILDING_FOR = Regex("""^Building for \w+\.\.\.$""")
        private val FOOTNOTE = Regex("""^\[#[^\]]+\]: <.*>$""")

        private val HEADER = Regex("""^(.+?):(\d+):(?:(\d+):)? (fatal error|error|warning|note|remark): (.*)$""")
        private val SOURCE_LINE = Regex("""^\s*(\d+)\s*[|│](.*)$""")
        private val MARKER_LINE = Regex("""^\s*[|│](.*)$""")
        private val INLINE_MARKER = Regex("""^(.*?)[`|]- (fatal error|error|warning|note|remark): (.*)$""")
        private val ELISION = Regex("""^\s*[:…]\s*$""")
        private val BOX_LINE = Regex("""^\s*[╭╰├┬┴─].*$""")
        private val CARET_LINE = Regex("""^[\s~]*\^[\s~^]*$""")
        private val CATEGORY = Regex("""\s+\[#[\w-]+]$""")

        private val UNDEFINED_SYMBOLS = Regex("""^Undefined symbols for architecture (\S+):$""")
        private val UNDEFINED_SYMBOL = Regex("""^\s+"(.+)", referenced from:$""")
        private val SYMBOLS_NOT_FOUND = Regex("""^ld: symbol\(s\) not found.*$""")
        private val LINK_FAILED = Regex("""^(?:[\w+.-]+: )?error: link(?:er)? command failed with exit code (\d+).*$""")
        private val LD = Regex("""^(?:\S*/)?(?:ld|ld\.[\w.-]+|ld64(?:\.[\w-]+)?|lld): (.+)$""")
        private val LD_CONTEXT = Regex("""^(?:\S*/)?(?:ld|ld\.[\w.-]+|lld): .*: in function [`'].*':$""")
        private val UNDEFINED_REFERENCE = Regex("""^(.+?):(?:(\d+):)? undefined reference to [`'](.+)'$""")

        private val BUILD_FAILED = Regex("""^error: (?:Build failed|fatalError)\.?$""")
        private val TASK_FAILED = Regex("""^error: (.+?) failed with a nonzero exit code\..*$""")
        private val SWIFTPM_MESSAGE = Regex("""^(?:(clang|clang\+\+|swiftc|swift-frontend|swift-driver): )?(error|warning|note|remark): (.+)$""")

        /** swift-build prefixes some lines with the manifest and the product: `/p/Package.swift: greet-product: ...`. */
        private val TASK_PREFIX = Regex("""^(/.*?Package\.swift|[A-Za-z]:\\.*?Package\.swift): \S+?: (.+)$""")

        private fun withoutTaskPrefix(text: String): String = TASK_PREFIX.matchEntire(text)?.groupValues?.get(2) ?: text

        /** The message without its trailing diagnostic group reference (`[#NoUsage]`), which the description keeps. */
        private fun title(message: String): String = message.replace(CATEGORY, "")

        private fun kindOf(severity: String): MessageEvent.Kind = when (severity) {
            "error", "fatal error" -> MessageEvent.Kind.ERROR
            "warning" -> MessageEvent.Kind.WARNING
            else -> MessageEvent.Kind.INFO
        }
    }
}

/** Normalizes a line of `swift build` output for parsing. */
internal object SwiftOutputText {
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val ESCAPE = Regex("\u001B[@-Z\\\\-_]")

    /** [line] without its line terminator and terminal escapes (colors, and OSC 8 links to diagnostic docs). */
    fun clean(line: String): String {
        val text = line.trimEnd('\n', '\r')
        if ('\u001B' !in text) return text
        return text.replace(OSC, "").replace(CSI, "").replace(ESCAPE, "")
    }
}
