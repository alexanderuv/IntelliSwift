package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.LanguageNamesValidation
import com.intellij.lang.cacheBuilder.WordOccurrence
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.structure.SwiftBreadcrumbsProvider

/** Find usages provider, names validator and breadcrumbs. */
class SwiftNavigationSupportTest : BasePlatformTestCase() {

    private fun declarationAtCaret(): SwiftNamedDeclaration {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        return PsiTreeUtil.getParentOfType(leaf, SwiftNamedDeclaration::class.java)!!
    }

    fun testFindUsagesProvider() {
        myFixture.configureByText("a.swift", "struct S {\n    func <caret>move(_ x: Int, to y: Int) {}\n}")
        val provider = LanguageFindUsages.INSTANCE.forLanguage(SwiftLanguage)
        assertInstanceOf(provider, SwiftFindUsagesProvider::class.java)
        val decl = declarationAtCaret()
        assertTrue(provider.canFindUsagesFor(decl))
        assertFalse(provider.canFindUsagesFor(decl.parent))
        assertEquals("method", provider.getType(decl))
        assertEquals("move(_:to:)", provider.getDescriptiveName(decl))
        assertEquals("move", provider.getNodeText(decl, true))
    }

    fun testFindUsagesKinds() {
        myFixture.configureByText("a.swift", "let <caret>g = 1")
        val provider = LanguageFindUsages.INSTANCE.forLanguage(SwiftLanguage)
        assertEquals("global variable", provider.getType(declarationAtCaret()))
        myFixture.configureByText("a.swift", "class C { var <caret>p = 1 }")
        assertEquals("property", provider.getType(declarationAtCaret()))
        myFixture.configureByText("a.swift", "enum E { case <caret>a }")
        assertEquals("enum case", provider.getType(declarationAtCaret()))
    }

    fun testWordsScanner() {
        val scanner = SwiftFindUsagesProvider().wordsScanner
        val text = "let `class` = foo // bar\nlet s = \"baz\""
        val words = ArrayList<Pair<String, WordOccurrence.Kind?>>()
        scanner.processWords(text) { occurrence ->
            words += occurrence.baseText.subSequence(occurrence.start, occurrence.end).toString() to occurrence.kind
            true
        }
        assertTrue(words.toString(), "class" to WordOccurrence.Kind.CODE in words)
        assertTrue(words.toString(), "foo" to WordOccurrence.Kind.CODE in words)
        assertTrue(words.toString(), "bar" to WordOccurrence.Kind.COMMENTS in words)
        assertTrue(words.toString(), "baz" to WordOccurrence.Kind.LITERALS in words)
    }

    fun testNamesValidator() {
        val validator = LanguageNamesValidation.INSTANCE.forLanguage(SwiftLanguage)
        assertInstanceOf(validator, SwiftNamesValidator::class.java)
        assertTrue(validator.isIdentifier("foo", project))
        assertTrue(validator.isIdentifier("`class`", project))
        assertTrue(validator.isIdentifier("get", project))
        assertFalse(validator.isIdentifier("class", project))
        assertFalse(validator.isIdentifier("_", project))
        assertFalse(validator.isIdentifier("a b", project))
        assertFalse(validator.isIdentifier("1a", project))
        assertFalse(validator.isIdentifier("", project))
        assertTrue(validator.isKeyword("class", project))
        assertFalse(validator.isKeyword("get", project))
    }

    fun testBreadcrumbs() {
        myFixture.configureByText(
            "a.swift",
            "extension Foo {\n    struct Bar {\n        var x: Int {\n            get { <caret>1 }\n        }\n    }\n}",
        )
        val provider = BreadcrumbsProvider.EP_NAME.extensionList.single { it is SwiftBreadcrumbsProvider }
        val crumbs = ArrayList<String>()
        var e: PsiElement? = myFixture.file.findElementAt(myFixture.caretOffset)
        while (e != null && e != myFixture.file) {
            if (provider.acceptElement(e)) crumbs.add(0, provider.getElementInfo(e))
            e = e.parent
        }
        assertEquals(listOf("extension Foo", "Bar", "x", "get"), crumbs)
    }
}
