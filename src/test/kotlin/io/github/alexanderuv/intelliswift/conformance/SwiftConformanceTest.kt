package io.github.alexanderuv.intelliswift.conformance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.ParsingTestCase
import io.github.alexanderuv.intelliswift.SwiftTestSupport
import io.github.alexanderuv.intelliswift.SwiftTestSupport.Decl
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes
import io.github.alexanderuv.intelliswift.parser.SwiftParserDefinition
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Compares our parser with swift-syntax on a corpus. The swift-syntax side comes from the
 * `tools/swift-syntax-oracle` JSON Lines output, passed with `-PoracleOutput=<file>`; see
 * `scripts/conformance.sh`. Without it the test does nothing.
 *
 * For every file swift-syntax parses without errors, we require:
 * - no error elements in our tree,
 * - the same significant tokens (kinds and text, after [SwiftTestSupport.normalize]),
 * - the same declaration skeleton.
 * For files swift-syntax rejects, we require at least one error element.
 *
 * The full report goes to `build/reports/conformance/report.txt`. `-PconformanceMaxFailures=<n>`
 * tolerates up to `n` differing files.
 */
class SwiftConformanceTest : ParsingTestCase("", "swift", SwiftParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    private class Failure(val path: String, val category: String, val detail: String, val signature: String)

    fun testCorpus() {
        val oracle = System.getProperty("intelliswift.oracle.output").orEmpty()
        if (oracle.isEmpty()) {
            println("SwiftConformanceTest: no oracle output given (-PoracleOutput=...); skipping.")
            return
        }
        val maxFailures = System.getProperty("intelliswift.conformance.maxFailures")?.toIntOrNull() ?: 0
        val failures = ArrayList<Failure>()
        var files = 0
        var clean = 0
        File(oracle).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val json = JsonParser.parseString(line).asJsonObject
                if (json.has("error")) continue
                files++
                val path = json["path"].asString
                val text = readSource(File(path)) ?: continue
                val result = check(path, text, json)
                if (result.isEmpty()) clean++ else failures += result
            }
        }
        writeReport(files, clean, failures)
        val failedFiles = failures.map { it.path }.distinct().size
        println("SwiftConformanceTest: $clean/$files files conform; $failedFiles files differ. Report: $REPORT")
        if (failedFiles > maxFailures) {
            fail("$failedFiles of $files files differ from swift-syntax (allowed: $maxFailures). See $REPORT")
        }
    }

    private fun readSource(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /**
     * The editor highlighter restarts the lexer at tokens whose state is 0. Restarting at a sample
     * of those tokens must reproduce the tokens of a full pass.
     */
    private fun checkLexerRestarts(path: String, text: String): Failure? {
        data class Tok(val type: Any?, val start: Int, val end: Int, val state: Int)
        fun lex(from: Int, limit: Int): List<Tok> {
            val lexer = SwiftLexer()
            lexer.start(text, from, text.length, 0)
            val out = ArrayList<Tok>()
            while (lexer.tokenType != null && out.size < limit) {
                out += Tok(lexer.tokenType, lexer.tokenStart, lexer.tokenEnd, lexer.state)
                lexer.advance()
            }
            return out
        }
        val full = lex(0, Int.MAX_VALUE)
        val restartPoints = full.indices.filter { full[it].state == 0 }
        val step = maxOf(1, restartPoints.size / 40)
        for (i in restartPoints.indices step step) {
            val index = restartPoints[i]
            val expected = full.subList(index, minOf(full.size, index + 200))
            val actual = lex(full[index].start, expected.size)
            val differs = expected.indices.firstOrNull { k -> k >= actual.size || expected[k].copy(state = 0) != actual[k].copy(state = 0) }
            if (differs != null) {
                val at = expected[differs].start
                val line = text.substring(0, at).count { it == '\n' } + 1
                return Failure(path, "lexer-restart", "restart at offset ${full[index].start} diverges at line $line", "lexer restart diverges")
            }
        }
        return null
    }

    private fun check(path: String, text: String, json: JsonObject): List<Failure> {
        val psi = createPsiFile("corpus", text)
        val root = psi.node
        val oracleErrors = json["diagnostics"].asJsonArray.map { it.asJsonObject }.filter { it["severity"].asString == "error" }
        val ourErrors = SwiftTestSupport.errors(root)
        val out = ArrayList<Failure>()
        checkLexerRestarts(path, text)?.let { out += it }

        if (oracleErrors.isNotEmpty()) {
            if (ourErrors.isEmpty() && !text.contains("<#")) {
                // (Editor placeholders are errors to swift-syntax; we parse them as identifiers.)
                val d = oracleErrors.first()
                out += Failure(path, "missed-error", "${d["line"]}:${d["column"]} ${d["message"].asString}", "missed: ${d["message"].asString.take(60)}")
            }
            return out
        }

        if (ourErrors.isNotEmpty()) {
            val e = ourErrors.first()
            val lineNo = text.substring(0, e.textOffset).count { it == '\n' } + 1
            val context = text.lines().getOrNull(lineNo - 1)?.trim()?.take(120)
            out += Failure(path, "false-error", "line $lineNo: ${e.errorDescription}\n      | $context", "false: ${e.errorDescription}")
        }

        // An escaped newline in a multi-line string is trivia in swift-syntax and a token for us.
        val ours = SwiftTestSupport.normalize(
            SwiftTestSupport.leaves(root)
                .filterNot { it.elementType == SwiftTokenTypes.ESCAPE_SEQUENCE && it.text.any { c -> c == '\n' || c == '\r' } }
                .map { SwiftTestSupport.oracleKind(it.elementType) to it.text },
        )
        val theirs = SwiftTestSupport.normalize(json["tokens"].asJsonArray.map { val a = it.asJsonArray; a[0].asString to a[1].asString })
        val firstDiff = ours.indices.firstOrNull { it >= theirs.size || ours[it] != theirs[it] }
            ?: if (theirs.size > ours.size) ours.size else null
        if (firstDiff != null) {
            fun window(list: List<Pair<String, String>>) =
                list.subList(maxOf(0, firstDiff - 4), minOf(list.size, firstDiff + 3)).joinToString(" ") { "${it.first}(${it.second})" }
            val o = ours.getOrNull(firstDiff)
            val t = theirs.getOrNull(firstDiff)
            out += Failure(
                path, "tokens",
                "at token $firstDiff\n      ours:   ${window(ours)}\n      theirs: ${window(theirs)}",
                "token: ours ${o?.first}(${o?.second?.take(20)}) vs theirs ${t?.first}(${t?.second?.take(20)})",
            )
        }

        val ourDecls = SwiftTestSupport.decls(root).joinToString("") { it.render() }
        val theirDecls = parseDecls(json["decls"].asJsonArray).joinToString("") { it.render() }
        if (ourDecls != theirDecls) {
            val a = ourDecls.lines()
            val b = theirDecls.lines()
            val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
            out += Failure(
                path, "decls",
                "at line $i\n      ours:   ${a.getOrNull(i)?.trim()}\n      theirs: ${b.getOrNull(i)?.trim()}",
                "decl: ours ${a.getOrNull(i)?.trim()?.substringBefore(' ')} vs theirs ${b.getOrNull(i)?.trim()?.substringBefore(' ')}",
            )
        }
        return out
    }

    private fun parseDecls(array: JsonArray): List<Decl> = array.map { el ->
        val o = el.asJsonObject
        Decl(
            o["kind"].asString,
            o["name"].takeUnless { it.isJsonNull }?.asString,
            parseDecls(o["children"].asJsonArray),
        )
    }

    private fun writeReport(files: Int, clean: Int, failures: List<Failure>) {
        val report = File(REPORT)
        report.parentFile.mkdirs()
        report.bufferedWriter().use { w ->
            w.write("Conformance: $clean/$files files match swift-syntax\n\n")
            w.write("Most common differences:\n")
            failures.groupingBy { it.signature }.eachCount().entries.sortedByDescending { it.value }.take(80).forEach { (sig, n) ->
                w.write(String.format("%6d  %s\n", n, sig))
            }
            for ((category, list) in failures.groupBy { it.category }) {
                w.write("\n== $category (${list.size})\n")
                for (f in list.take(300)) w.write("  ${f.path}\n      ${f.detail}\n")
            }
        }
    }

    companion object {
        private const val REPORT = "build/reports/conformance/report.txt"
    }
}
