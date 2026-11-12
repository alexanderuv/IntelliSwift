package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.highlighting.SwiftHighlighterColors as C

class SwiftHighlightingAnnotatorTest : BasePlatformTestCase() {

    private fun annotatorInfos(text: String): List<HighlightInfo> {
        myFixture.configureByText("a.swift", text)
        return myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null || it.forcedTextAttributes != null }
    }

    /** `(text, key)` for every range the annotator colours. */
    private fun keys(text: String): List<Pair<String, TextAttributesKey>> =
        annotatorInfos(text).mapNotNull { info -> info.forcedTextAttributesKey?.let { text.substring(info.startOffset, info.endOffset) to it } }

    private fun erased(text: String): List<String> =
        annotatorInfos(text).filter { it.forcedTextAttributes === TextAttributes.ERASE_MARKER }.map { text.substring(it.startOffset, it.endOffset) }

    private fun assertHighlighted(text: String, vararg expected: Pair<String, TextAttributesKey>) {
        val actual = keys(text)
        for (e in expected) assertTrue("expected $e in $actual", e in actual)
    }

    fun testContextualKeywords() {
        val text = """
            actor A {
                nonisolated func f() async throws -> some P { fatalError() }
                var x: Int { get { 1 } set { } }
                lazy var y = 1
            }
        """.trimIndent()
        assertHighlighted(
            text,
            "actor" to C.KEYWORD, "nonisolated" to C.KEYWORD, "async" to C.KEYWORD, "some" to C.KEYWORD,
            "get" to C.KEYWORD, "set" to C.KEYWORD, "lazy" to C.KEYWORD,
        )
        // Reserved keywords are the lexer's job.
        assertFalse(keys(text).any { it.first == "func" || it.first == "var" || it.first == "throws" })
    }

    fun testContextualKeywordUsedAsNameIsNotAKeyword() {
        val text = "let get = 1\nlet async = get"
        assertFalse(keys(text).any { it.second == C.KEYWORD })
    }

    fun testReservedWordsUsedAsNamesAreErased() {
        val text = "let a = f(for: 1, in: 2)\nlet b = E.default"
        assertEquals(listOf("for", "in", "default"), erased(text))
    }

    fun testAttributesAndMacros() {
        val text = "@MainActor @available(iOS 17, *) final class C {}\nlet f = #file\n#warning(\"x\")"
        assertHighlighted(text, "MainActor" to C.ATTRIBUTE, "available" to C.ATTRIBUTE, "file" to C.DIRECTIVE, "warning" to C.DIRECTIVE)
    }

    fun testDeclarationNames() {
        val text = """
            class K {}
            struct S<T> {}
            enum E { case a, b(Int) }
            protocol P { associatedtype Item }
            actor Act {}
            typealias Alias = Int
            func top(_ first: Int, label second: Int) {}
            macro m() = #externalMacro(module: "M", type: "T")
            let global = 1
            struct Holder {
                var prop = 1
                func method() { let local = 2; _ = [1].map { item in item } }
                static func == (lhs: Holder, rhs: Holder) -> Bool { true }
            }
        """.trimIndent()
        assertHighlighted(
            text,
            "K" to C.CLASS_NAME, "S" to C.CLASS_NAME, "T" to C.TYPE_PARAMETER, "E" to C.CLASS_NAME,
            "a" to C.ENUM_CASE, "b" to C.ENUM_CASE, "P" to C.PROTOCOL_NAME, "Item" to C.CLASS_NAME,
            "Act" to C.CLASS_NAME, "Alias" to C.CLASS_NAME, "top" to C.FUNCTION_DECLARATION, "m" to C.FUNCTION_DECLARATION,
            "first" to C.PARAMETER, "second" to C.PARAMETER, "global" to C.GLOBAL_VARIABLE, "prop" to C.PROPERTY,
            "method" to C.FUNCTION_DECLARATION, "local" to C.LOCAL_VARIABLE, "item" to C.PARAMETER,
            "==" to C.FUNCTION_DECLARATION, "lhs" to C.PARAMETER,
        )
        // Argument labels are not parameter names.
        assertFalse(keys(text).any { it.first == "label" })
    }

    fun testStringInterpolationDelimiters() {
        val text = "let s = \"a \\(x) b\""
        val escapes = keys(text).filter { it.second == C.VALID_ESCAPE }.map { it.first }
        assertEquals(listOf("\\(", ")"), escapes)
        val raw = "let s = #\"a \\#(x) b\"#"
        assertEquals(listOf("\\#(", ")"), keys(raw).filter { it.second == C.VALID_ESCAPE }.map { it.first })
    }

    fun testOnlyLeavesAreAnnotated() {
        val text = "struct S { var x: Int { get { 1 } } }"
        for (info in annotatorInfos(text)) {
            val element = myFixture.file.findElementAt(info.startOffset)!!
            assertEquals(element.textRange.startOffset to element.textRange.endOffset, info.startOffset to info.endOffset)
        }
    }
}
