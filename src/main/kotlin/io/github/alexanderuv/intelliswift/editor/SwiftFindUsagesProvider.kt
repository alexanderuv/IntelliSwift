package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.psi.SwiftDeclarationPresentation
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/**
 * Lets "Find Usages" start from any named declaration. Until references resolve, the platform
 * falls back to a text search for the name, which the words index built here makes fast.
 */
class SwiftFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        SwiftLexer(),
        TokenSet.create(T.IDENTIFIER, T.DOLLAR_IDENTIFIER),
        T.COMMENTS,
        T.STRING_CONTENT,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is SwiftNamedDeclaration && element.name != null

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = SwiftDeclarationPresentation.kindName(element)

    override fun getDescriptiveName(element: PsiElement): String =
        SwiftDeclarationPresentation.presentableText(element) ?: (element as? SwiftNamedDeclaration)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        (element as? SwiftNamedDeclaration)?.name ?: element.text
}
