package io.github.alexanderuv.intelliswift.structure

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage
import io.github.alexanderuv.intelliswift.psi.SwiftDeclarationPresentation
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import javax.swing.Icon

/** Breadcrumbs for the enclosing types, extensions, functions, properties and accessors. */
class SwiftBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(SwiftLanguage)

    override fun acceptElement(element: PsiElement): Boolean =
        element.node?.elementType in ACCEPTED && SwiftDeclarationPresentation.presentableText(element) != null

    override fun getElementInfo(element: PsiElement): String = SwiftDeclarationPresentation.presentableText(element) ?: ""

    override fun getElementIcon(element: PsiElement): Icon? = SwiftDeclarationPresentation.icon(element)

    private companion object {
        val ACCEPTED = setOf(
            E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL, E.PROTOCOL_DECL, E.EXTENSION_DECL,
            E.FUNCTION_DECL, E.INITIALIZER_DECL, E.DEINITIALIZER_DECL, E.SUBSCRIPT_DECL, E.VARIABLE_DECL,
            E.ACCESSOR_DECL, E.MACRO_DECL,
        )
    }
}
