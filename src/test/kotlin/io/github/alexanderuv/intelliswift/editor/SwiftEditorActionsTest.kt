package io.github.alexanderuv.intelliswift.editor

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Commenting, brace matching and the typing behaviour that comes with the brace matcher and quote handler. */
class SwiftEditorActionsTest : BasePlatformTestCase() {

    private fun configure(text: String) = myFixture.configureByText("a.swift", text)

    // region Commenter

    fun testLineComment() {
        configure("<caret>let x = 1\nlet y = 2")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("//let x = 1\n<caret>let y = 2")
    }

    fun testLineUncomment() {
        configure("//<caret>let x = 1")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("let x = 1")
    }

    fun testBlockComment() {
        configure("let x = <selection>1 + 2</selection>")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        myFixture.checkResult("let x = /*1 + 2*/")
    }

    // endregion

    // region Brace matching

    private fun assertMatches(text: String, open: String, close: String) {
        configure(text)
        val openOffset = text.indexOf(open)
        val closeOffset = text.lastIndexOf(close)
        myFixture.editor.caretModel.moveToOffset(openOffset)
        assertEquals(closeOffset, BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file))
        myFixture.editor.caretModel.moveToOffset(closeOffset)
        assertEquals(openOffset, BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, false, myFixture.file))
    }

    fun testParenthesesMatch() = assertMatches("let x = f(g(1), 2)", "(", ")")

    fun testBracketsMatch() = assertMatches("let x = [[1], [2]]", "[", "]")

    fun testBracesMatch() = assertMatches("struct S { func f() { } }", "{", "}")

    fun testBracesAreStructural() {
        configure("struct S {}")
        val iterator = myFixture.editor.highlighter.createIterator("struct S ".length)
        assertTrue(BraceMatchingUtil.isStructuralBraceToken(myFixture.file.fileType, iterator, myFixture.file.text))
    }

    // endregion

    // region Typing

    fun testParenthesisIsPaired() {
        configure("let x = f<caret>")
        myFixture.type('(')
        myFixture.checkResult("let x = f(<caret>)")
        myFixture.type(')')
        myFixture.checkResult("let x = f()<caret>")
    }

    fun testBracketIsPaired() {
        configure("let x = <caret>")
        myFixture.type('[')
        myFixture.checkResult("let x = [<caret>]")
    }

    fun testEnterAfterBraceClosesIt() {
        configure("func f() {<caret>")
        myFixture.type('\n')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.trimEnd().endsWith("}"))
    }

    fun testQuoteIsPaired() {
        configure("let s = <caret>")
        myFixture.type('"')
        myFixture.checkResult("let s = \"<caret>\"")
        myFixture.type("abc")
        myFixture.type('"')
        myFixture.checkResult("let s = \"abc\"<caret>")
    }

    fun testQuoteBeforeExistingStringIsNotSkipped() {
        configure("let s = <caret>\"abc\"")
        myFixture.type('"')
        assertEquals("let s = \"\"abc\"".length, myFixture.editor.document.textLength)
    }

    fun testQuoteClosingInterpolatedString() {
        configure("let s = \"a\\(x)<caret>\"")
        myFixture.type('"')
        myFixture.checkResult("let s = \"a\\(x)\"<caret>")
    }

    fun testQuoteInsideStringIsNotPaired() {
        configure("let s = \"ab<caret>c\"")
        myFixture.type('"')
        myFixture.checkResult("let s = \"ab\"<caret>c\"")
    }

    // endregion
}
