package io.github.alexanderuv.intelliswift.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.alexanderuv.intelliswift.lang.SwiftFileType
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

class SwiftFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SwiftLanguage) {
    override fun getFileType(): FileType = SwiftFileType
    override fun toString(): String = "Swift file"

    /** Top-level declarations, including those nested in `#if` blocks. */
    val declarations: List<SwiftDeclaration>
        get() = declarationsInScope()
}

/**
 * Declarations directly in this element's scope, looking through `#if` clauses. A MISSING_DECL
 * (attributes or modifiers with no declaration after them) is not one.
 */
internal fun PsiElement.declarationsInScope(): List<SwiftDeclaration> = buildList {
    for (child in this@declarationsInScope.children) {
        val type = child.node.elementType
        val transparent = type == E.IF_CONFIG_DECL || type == E.IF_CONFIG_CLAUSE
        if (child is SwiftDeclaration && !transparent && type != E.MISSING_DECL) add(child)
        if (transparent) addAll(child.declarationsInScope())
    }
}

open class SwiftPsiElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** Any declaration node. */
open class SwiftDeclaration(node: ASTNode) : SwiftPsiElement(node) {
    /** The members of a type or extension body, looking through `#if`. */
    val members: List<SwiftDeclaration>
        get() = findChildByType<PsiElement>(E.MEMBER_BLOCK)?.declarationsInScope() ?: emptyList()

    /** Variables declare one name per binding; most other declarations name one thing. */
    open val bindings: List<SwiftNamedDeclaration> get() = emptyList()

    /** The `///` or `/** */` comments the parser bound to this declaration, in source order. */
    val docComments: List<PsiComment>
        get() = buildList {
            var c = node.firstChildNode
            while (c != null && (c.elementType == SwiftTokenTypes.WHITE_SPACE || c.elementType in SwiftTokenTypes.COMMENTS)) {
                (c.psi as? PsiComment)?.let { add(it) }
                c = c.treeNext
            }
        }

    /** Skips the bound doc comments, so navigation lands on the declaration itself. */
    override fun getTextOffset(): Int {
        var c = node.firstChildNode
        while (c != null && (c.elementType == SwiftTokenTypes.WHITE_SPACE || c.elementType in SwiftTokenTypes.COMMENTS)) c = c.treeNext
        return c?.startOffset ?: super.getTextOffset()
    }

    override fun getPresentation(): ItemPresentation? {
        val text = SwiftDeclarationPresentation.presentableText(this) ?: return super.getPresentation()
        return PresentationData(text, SwiftDeclarationPresentation.locationText(this), SwiftDeclarationPresentation.icon(this), null)
    }
}

/** A declaration with a single name: types, functions, type aliases, enum case elements, bound names. */
open class SwiftNamedDeclaration(node: ASTNode) : SwiftDeclaration(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        val operatorName = node.elementType == E.FUNCTION_DECL || node.elementType == E.OPERATOR_DECL
        var child = node.firstChildNode
        var afterIntroducer = false
        while (child != null) {
            val type = child.elementType
            if (afterIntroducer) {
                if (type == SwiftTokenTypes.IDENTIFIER) return child.psi
                if (operatorName && type in SwiftTokenTypes.OPERATORS) return child.psi
                if (type != SwiftTokenTypes.WHITE_SPACE && type !in SwiftTokenTypes.COMMENTS) return null
            } else if (isIntroducer(child)) {
                afterIntroducer = true
                if (node.elementType == E.ENUM_CASE_ELEMENT || node.elementType == E.IDENTIFIER_PATTERN ||
                    node.elementType == E.GENERIC_PARAMETER
                ) return child.psi
            }
            child = child.treeNext
        }
        return null
    }

    private fun isIntroducer(child: ASTNode): Boolean {
        val t = child.elementType
        return when (node.elementType) {
            E.ENUM_CASE_ELEMENT, E.IDENTIFIER_PATTERN -> t == SwiftTokenTypes.IDENTIFIER
            E.GENERIC_PARAMETER -> t == SwiftTokenTypes.IDENTIFIER
            else -> t is io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType && t.keyword in INTRODUCERS
        }
    }

    override fun getName(): String? = nameIdentifier?.text?.removeSurrounding("`")

    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Renaming is not supported yet")

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    companion object {
        private val INTRODUCERS = setOf(
            "class", "struct", "enum", "protocol", "actor", "func", "typealias", "associatedtype", "macro",
            "operator", "precedencegroup",
        )
    }
}

class SwiftVariableDeclaration(node: ASTNode) : SwiftDeclaration(node) {
    override val bindings: List<SwiftNamedDeclaration>
        get() = PsiTreeUtil.findChildrenOfType(this, SwiftNamedDeclaration::class.java)
            .filter { it.node.elementType == E.IDENTIFIER_PATTERN && PsiTreeUtil.getParentOfType(it, SwiftVariableDeclaration::class.java) == this }
            .filter { pattern -> generateSequence(pattern.parent) { it.parent }.takeWhile { it != this }
                .none { it.node.elementType == E.INITIALIZER_CLAUSE || it.node.elementType == E.ACCESSOR_BLOCK } }
}

class SwiftEnumCaseDeclaration(node: ASTNode) : SwiftDeclaration(node) {
    override val bindings: List<SwiftNamedDeclaration>
        get() = children.filterIsInstance<SwiftNamedDeclaration>()
}

class SwiftExtensionDeclaration(node: ASTNode) : SwiftDeclaration(node) {
    /** The extended type as written, e.g. `Array where Element: Equatable` gives `Array`. */
    val extendedTypeText: String?
        get() = children.firstOrNull { it.node.elementType in TYPE_NODES }?.text

    companion object {
        private val TYPE_NODES = setOf(E.IDENTIFIER_TYPE, E.MEMBER_TYPE, E.GENERIC_ARGUMENT_CLAUSE, E.ARRAY_TYPE,
            E.DICTIONARY_TYPE, E.OPTIONAL_TYPE, E.TUPLE_TYPE, E.ATTRIBUTED_TYPE, E.COMPOSITION_TYPE)
    }
}

object SwiftPsiFactory {
    private val NAMED = setOf(
        E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL, E.PROTOCOL_DECL, E.FUNCTION_DECL, E.TYPE_ALIAS_DECL,
        E.ASSOCIATED_TYPE_DECL, E.ENUM_CASE_ELEMENT, E.OPERATOR_DECL, E.PRECEDENCE_GROUP_DECL, E.MACRO_DECL,
        E.IDENTIFIER_PATTERN, E.GENERIC_PARAMETER,
    )

    fun createElement(node: ASTNode): PsiElement {
        val type = node.elementType
        return when {
            type == E.VARIABLE_DECL -> SwiftVariableDeclaration(node)
            type == E.ENUM_CASE_DECL -> SwiftEnumCaseDeclaration(node)
            type == E.EXTENSION_DECL -> SwiftExtensionDeclaration(node)
            type in NAMED -> SwiftNamedDeclaration(node)
            type in E.DECLARATIONS -> SwiftDeclaration(node)
            else -> SwiftPsiElement(node)
        }
    }
}
