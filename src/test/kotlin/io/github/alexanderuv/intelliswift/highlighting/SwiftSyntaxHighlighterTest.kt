package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.highlighting.SwiftHighlighterColors as C

class SwiftSyntaxHighlighterTest : BasePlatformTestCase() {

    /** `(token text, key)` for every non-whitespace token, using the registered highlighter. */
    private fun highlights(text: String): List<Pair<String, TextAttributesKey?>> {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(SwiftLanguage, project, null)
        assertInstanceOf(highlighter, SwiftSyntaxHighlighter::class.java)
        val lexer = SwiftLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Pair<String, TextAttributesKey?>>()
        while (lexer.tokenType != null) {
            val tokenText = text.substring(lexer.tokenStart, lexer.tokenEnd)
            if (tokenText.isNotBlank()) out += tokenText to highlighter.getTokenHighlights(lexer.tokenType).singleOrNull()
            lexer.advance()
        }
        return out
    }

    private fun assertKey(text: String, token: String, expected: TextAttributesKey?) {
        val found = highlights(text).filter { it.first == token }
        assertTrue("token '$token' not found in ${highlights(text)}", found.isNotEmpty())
        for ((_, key) in found) assertEquals("key of '$token'", expected, key)
    }

    fun testKeywords() {
        val text = "func f() { return self.x; super.y; Self.z; _ = 1 }"
        assertKey(text, "func", C.KEYWORD)
        assertKey(text, "return", C.KEYWORD)
        assertKey(text, "self", C.SELF)
        assertKey(text, "super", C.SELF)
        assertKey(text, "Self", C.SELF)
        assertKey(text, "_", C.KEYWORD)
        assertKey(text, "f", C.IDENTIFIER)
    }

    fun testContextualKeywordsAreIdentifiersForTheLexer() {
        assertKey("var x: Int { get { 1 } }", "get", C.IDENTIFIER)
    }

    fun testLiterals() {
        val text = """let a = 0x1F + 1.5e3; let s = "a\n\q\(a)"; let r = /[a-z]+/"""
        assertKey(text, "0x1F", C.NUMBER)
        assertKey(text, "1.5e3", C.NUMBER)
        assertKey(text, "\"", C.STRING)
        assertKey(text, "\\n", C.VALID_ESCAPE)
        assertKey(text, "\\q", C.INVALID_ESCAPE)
        assertKey(text, "[a-z]+", C.REGEX)
        assertKey(text, "/", C.REGEX)
    }

    fun testMultilineString() {
        val text = "let s = \"\"\"\n  hi\n  \"\"\""
        assertKey(text, "\"\"\"", C.STRING)
    }

    fun testComments() {
        val text = "// line\n/* block */\n/// doc\n/** doc block */\nlet x = 1"
        assertKey(text, "// line", C.LINE_COMMENT)
        assertKey(text, "/* block */", C.BLOCK_COMMENT)
        assertKey(text, "/// doc", C.DOC_COMMENT)
        assertKey(text, "/** doc block */", C.DOC_COMMENT)
    }

    fun testPunctuationAndOperators() {
        val text = "let a = [b(c), d].e; f += g -> h"
        assertKey(text, "(", C.PARENTHESES)
        assertKey(text, ")", C.PARENTHESES)
        assertKey(text, "[", C.BRACKETS)
        assertKey(text, "]", C.BRACKETS)
        assertKey(text, ",", C.COMMA)
        assertKey(text, ";", C.SEMICOLON)
        assertKey(text, ".", C.DOT)
        assertKey(text, "=", C.OPERATOR)
        assertKey(text, "+=", C.OPERATOR)
        assertKey(text, "->", C.OPERATOR)
        assertKey("struct S {}", "{", C.BRACES)
    }

    fun testSplitOperatorPiecesAreOperators() {
        // The lexer splits a leading `<` or `>` off operators; every piece is still an operator.
        for ((token, key) in highlights("a <= b; c >>= d")) {
            if (token != "a" && token != "b" && token != "c" && token != "d" && token != ";") assertEquals(token, C.OPERATOR, key)
        }
    }

    fun testAttributesAndDirectives() {
        val text = "@MainActor\n#if DEBUG\nlet f = #file\n#elseif X\n#else\n#endif\nif #available(iOS 17, *) {}"
        assertKey(text, "@", C.ATTRIBUTE)
        assertKey(text, "MainActor", C.IDENTIFIER)
        assertKey(text, "#if", C.DIRECTIVE)
        assertKey(text, "#elseif", C.DIRECTIVE)
        assertKey(text, "#else", C.DIRECTIVE)
        assertKey(text, "#endif", C.DIRECTIVE)
        assertKey(text, "#available", C.DIRECTIVE)
        assertKey(text, "#", C.DIRECTIVE)
    }

    fun testBadCharacter() {
        assertKey("let a = ``", "``", C.BAD_CHARACTER)
    }
}
