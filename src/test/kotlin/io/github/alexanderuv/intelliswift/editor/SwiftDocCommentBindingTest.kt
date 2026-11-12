package io.github.alexanderuv.intelliswift.editor

import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.psi.SwiftDeclaration
import io.github.alexanderuv.intelliswift.psi.SwiftFile

/** Doc comments directly above a declaration are part of it; other comments are not. */
class SwiftDocCommentBindingTest : BasePlatformTestCase() {

    private fun declarations(text: String): List<SwiftDeclaration> {
        val file = myFixture.configureByText("a.swift", text) as SwiftFile
        return file.declarations
    }

    private fun docTexts(decl: SwiftDeclaration) = decl.docComments.map { it.text }

    fun testLineDocCommentsAreBound() {
        val (f) = declarations("/// One\n/// Two\nfunc f() {}")
        assertEquals(listOf("/// One", "/// Two"), docTexts(f))
        assertTrue(f.firstChild is PsiComment)
        assertEquals(0, f.textRange.startOffset)
    }

    fun testBlockDocCommentIsBound() {
        val (s) = declarations("/**\n Doc\n */\n@MainActor public struct S {}")
        assertEquals(listOf("/**\n Doc\n */"), docTexts(s))
    }

    fun testMembersAndNestedDeclarations() {
        val (s) = declarations("struct S {\n    /// The x.\n    var x = 1\n    func f() {\n        /// Local.\n        let y = 2\n    }\n}")
        val x = s.members.first()
        assertEquals(listOf("/// The x."), docTexts(x))
        val local = PsiTreeUtil.findChildrenOfType(s, SwiftDeclaration::class.java).first { it.text.startsWith("/// Local.") }
        assertEquals(listOf("/// Local."), docTexts(local))
    }

    fun testBlankLineSeparates() {
        val (f) = declarations("/// Detached\n\nfunc f() {}")
        assertEquals(emptyList<String>(), docTexts(f))
    }

    fun testBlankLineInsideRunStopsTheRun() {
        val (f) = declarations("/// Detached\n\n/// Attached\nfunc f() {}")
        assertEquals(listOf("/// Attached"), docTexts(f))
    }

    fun testPlainCommentsStayOutside() {
        val (f) = declarations("// Plain\nfunc f() {}")
        assertEquals(emptyList<String>(), docTexts(f))
        assertFalse(f.firstChild is PsiComment)
        val (g) = declarations("/// Doc\n// MARK: - Section\nfunc g() {}")
        assertEquals(emptyList<String>(), docTexts(g))
    }

    fun testTrailingCommentOfPreviousLineStaysOutside() {
        val (_, b) = declarations("let a = 1 /// about a\nlet b = 2")
        assertEquals(emptyList<String>(), docTexts(b))
    }

    fun testSameLineDocBlock() {
        val (f) = declarations("/** Doc */ func f() {}")
        assertEquals(listOf("/** Doc */"), docTexts(f))
    }
}
