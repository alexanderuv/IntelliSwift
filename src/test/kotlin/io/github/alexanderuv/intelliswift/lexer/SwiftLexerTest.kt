package io.github.alexanderuv.intelliswift.lexer

import io.github.alexanderuv.intelliswift.SwiftTestSupport
import org.junit.Assert.assertEquals
import org.junit.Test

class SwiftLexerTest {
    /** Significant tokens as `kind(text)`, using swift-syntax's token kind names. */
    private fun tokens(text: String): String =
        SwiftTestSupport.lex(text)
            .filter { it.first != SwiftTokenTypes.WHITE_SPACE }
            .joinToString(" ") { (type, t) -> "${SwiftTestSupport.oracleKind(type)}(${t.replace("\n", "\\n")})" }

    private fun check(text: String, expected: String) = assertEquals(expected, tokens(text))

    @Test fun `identifiers and keywords`() = check(
        "let x = _ ; func `class`() { $0 }",
        "keyword:let(let) identifier(x) equal(=) wildcard(_) semicolon(;) keyword:func(func) identifier(`class`) " +
            "leftParen(() rightParen()) leftBrace({) dollarIdentifier(\$0) rightBrace(})",
    )

    @Test fun `contextual keywords are identifiers`() = check("get set some any await", "identifier(get) identifier(set) identifier(some) identifier(any) identifier(await)")

    @Test fun `raw identifiers`() = check("func `test something`()", "keyword:func(func) identifier(`test something`) leftParen(() rightParen())")

    @Test fun `numbers`() = check(
        "0 1_000 0x1F 0o17 0b101 1.5 1e10 0x1p-2 x.0.1",
        "integerLiteral(0) integerLiteral(1_000) integerLiteral(0x1F) integerLiteral(0o17) integerLiteral(0b101) " +
            "floatLiteral(1.5) floatLiteral(1e10) floatLiteral(0x1p-2) identifier(x) period(.) integerLiteral(0) period(.) integerLiteral(1)",
    )

    @Test fun `operator fixity follows whitespace`() = check(
        "a + b; -a; a!; a?.b; a ?? b; !a",
        "identifier(a) binaryOperator(+) identifier(b) semicolon(;) prefixOperator(-) identifier(a) semicolon(;) " +
            "identifier(a) exclamationMark(!) semicolon(;) identifier(a) postfixQuestionMark(?) period(.) identifier(b) semicolon(;) " +
            "identifier(a) binaryOperator(??) identifier(b) semicolon(;) prefixOperator(!) identifier(a)",
    )

    @Test fun `special punctuators`() = check(
        "x = &y; f() -> T; a ? b : c; foo(&x)",
        "identifier(x) equal(=) prefixAmpersand(&) identifier(y) semicolon(;) identifier(f) leftParen(() rightParen()) arrow(->) " +
            "identifier(T) semicolon(;) identifier(a) infixQuestionMark(?) identifier(b) colon(:) identifier(c) semicolon(;) " +
            "identifier(foo) leftParen(() prefixAmpersand(&) identifier(x) rightParen())",
    )

    @Test fun `leading greater-than is split off operators`() = check(
        "a >> b; Array<Array<Int>>",
        "identifier(a) prefixOperator(>) postfixOperator(>) identifier(b) semicolon(;) identifier(Array) binaryOperator(<) " +
            "identifier(Array) binaryOperator(<) identifier(Int) binaryOperator(>) postfixOperator(>)",
    )

    @Test fun `leading less-than is split off operators`() = check(
        "a <= b; x<-1",
        "identifier(a) prefixOperator(<) equal(=) identifier(b) semicolon(;) identifier(x) binaryOperator(<) binaryOperator(-) integerLiteral(1)",
    )

    /** swift-syntax re-lexes the rest of a split token, where a left-bound `?` or `!` stands alone. */
    @Test fun `question and exclamation marks end operator pieces`() = check(
        "T<U>?>; a>!=b",
        "identifier(T) binaryOperator(<) identifier(U) binaryOperator(>) postfixQuestionMark(?) postfixOperator(>) semicolon(;) " +
            "identifier(a) binaryOperator(>) exclamationMark(!) equal(=) identifier(b)",
    )

    @Test fun `pieces of a split operator are flagged`() {
        val lexer = SwiftLexer()
        lexer.start("a >>= b", 0, 7, 0)
        val flags = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != SwiftTokenTypes.WHITE_SPACE) flags += "${lexer.tokenText}:${lexer.continuesOperator}"
            lexer.advance()
        }
        assertEquals(listOf("a:false", ">:false", ">:true", "=:true", "b:false"), flags)
    }

    @Test fun `dot operators`() = check("0..<n; a...b", "integerLiteral(0) binaryOperator(..<) identifier(n) semicolon(;) identifier(a) binaryOperator(...) identifier(b)")

    @Test fun `comments`() {
        val lexed = SwiftTestSupport.lex("// a\n/// b\n/* c /* nested */ d */ /** e */ x")
            .filter { it.first != SwiftTokenTypes.WHITE_SPACE }
            .map { it.first }
        assertEquals(
            listOf(
                SwiftTokenTypes.LINE_COMMENT, SwiftTokenTypes.DOC_LINE_COMMENT, SwiftTokenTypes.BLOCK_COMMENT,
                SwiftTokenTypes.DOC_BLOCK_COMMENT, SwiftTokenTypes.IDENTIFIER,
            ),
            lexed,
        )
    }

    @Test fun `string interpolation`() = check(
        "\"a\\(b + \"c\\(d)\")e\\n\"",
        "stringQuote(\") stringSegment(a) backslash(\\) leftParen(() identifier(b) binaryOperator(+) stringQuote(\") stringSegment(c) " +
            "backslash(\\) leftParen(() identifier(d) rightParen()) stringQuote(\") rightParen()) stringSegment(e) stringSegment(\\n) stringQuote(\")",
    )

    @Test fun `raw strings`() = check(
        "#\"a\\n\\#(x)\"#",
        "rawStringPoundDelimiter(#) stringQuote(\") stringSegment(a\\n) backslash(\\) rawStringPoundDelimiter(#) leftParen(() " +
            "identifier(x) rightParen()) stringQuote(\") rawStringPoundDelimiter(#)",
    )

    @Test fun `multiline strings`() = check(
        "\"\"\"\n  a \"quoted\"\n  b\n  \"\"\"",
        "multilineStringQuote(\"\"\") stringSegment(  a \"quoted\"\\n) stringSegment(  b\\n) stringSegment(  ) multilineStringQuote(\"\"\")",
    )

    @Test fun `unterminated string ends at the newline`() = check("\"abc\nlet", "stringQuote(\") stringSegment(abc) keyword:let(let)")

    @Test fun `regex literals`() = check(
        "let r = /a+b/; let s = #/x/y/#; let d = a / b / c",
        "keyword:let(let) identifier(r) equal(=) regexSlash(/) regexLiteralPattern(a+b) regexSlash(/) semicolon(;) " +
            "keyword:let(let) identifier(s) equal(=) regexPoundDelimiter(#) regexSlash(/) regexLiteralPattern(x/y) regexSlash(/) " +
            "regexPoundDelimiter(#) semicolon(;) keyword:let(let) identifier(d) equal(=) identifier(a) binaryOperator(/) " +
            "identifier(b) binaryOperator(/) identifier(c)",
    )

    @Test fun `pound keywords`() = check(
        "#if os(macOS)\n#elseif swift(>=6.0)\n#else\n#endif #available #warning",
        "poundIf(#if) identifier(os) leftParen(() identifier(macOS) rightParen()) poundElseif(#elseif) identifier(swift) leftParen(() " +
            // The lexer splits the leading `>` off `>=`; the parser merges the pieces back.
            "prefixOperator(>) equal(=) floatLiteral(6.0) rightParen()) poundElse(#else) poundEndif(#endif) poundAvailable(#available) pound(#) identifier(warning)",
    )

    @Test fun `editor placeholders are identifiers`() = check("foo(<#value#>)", "identifier(foo) leftParen(() identifier(<#value#>) rightParen())")

    @Test fun `tokens cover the input exactly`() {
        val text = "struct S<T> { var x: [T] = [] ; func f() async throws -> Int { \"\\(x.count)\" } }\n"
        assertEquals(text, SwiftTestSupport.lex(text).joinToString("") { it.second })
    }

    /** The highlighter restarts lexing at tokens whose state is 0; that must reproduce the same tokens. */
    @Test fun `lexing restarts cleanly at initial-state tokens`() {
        val text = """
            let s = "a\(b)c" ; let r = /x+/ ; let t = x / y
            let m = ""${'"'}
              line \(value)
              ""${'"'}
            func f() { try? /a/.match(s) }
        """.trimIndent()
        val full = lexWithStates(text, 0)
        for ((index, token) in full.withIndex()) {
            if (token.state != 0) continue
            val restarted = lexWithStates(text, token.start)
            assertEquals("restart at offset ${token.start}", full.subList(index, full.size).map { it.key }, restarted.map { it.key })
        }
    }

    private data class Lexed(val key: String, val start: Int, val state: Int)

    private fun lexWithStates(text: String, from: Int): List<Lexed> {
        val lexer = SwiftLexer()
        lexer.start(text, from, text.length, 0)
        val out = ArrayList<Lexed>()
        while (lexer.tokenType != null) {
            out += Lexed("${lexer.tokenType}@${lexer.tokenStart}-${lexer.tokenEnd}", lexer.tokenStart, lexer.state)
            lexer.advance()
        }
        return out
    }
}
