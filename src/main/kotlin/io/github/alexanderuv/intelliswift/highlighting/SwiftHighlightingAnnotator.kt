package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywords
import io.github.alexanderuv.intelliswift.psi.SwiftDeclarationPresentation
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.highlighting.SwiftHighlighterColors as C
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/**
 * Highlighting that needs the parse tree: contextual keywords (lexed as identifiers and remapped
 * by the parser), reserved words the parser turned into names (`case .default`, `for:` labels),
 * attribute and macro names, declaration names and parameters, and string interpolation delimiters.
 *
 * Works on leaves (plus interpolation segments) and looks only at a few ancestors, so it stays
 * cheap on large files.
 */
class SwiftHighlightingAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        if (node.elementType == E.EXPRESSION_SEGMENT) return interpolationDelimiters(node, holder)
        if (node.firstChildNode != null) return
        val type = node.elementType
        val parent = node.treeParent ?: return
        val parentType = parent.elementType

        when {
            type is SwiftKeywordTokenType -> {
                val nameKey = when {
                    isAttributeName(parent) -> C.ATTRIBUTE
                    isMacroName(node, parentType) -> C.DIRECTIVE
                    else -> null
                }
                when {
                    nameKey != null -> {
                        if (type.isReserved) erase(element, holder)
                        highlight(element, holder, nameKey)
                    }
                    !type.isReserved -> highlight(element, holder, C.KEYWORD)
                }
            }
            type == T.IDENTIFIER -> {
                // The lexer highlighted this as a keyword; the parser says it is a name.
                if (isReservedWord(node)) erase(element, holder)
                val key = identifierKey(node, parent, parentType)
                if (key != null) highlight(element, holder, key)
            }
            else -> {
                // Operator names of `func ==` and friends.
                if (type in T.OPERATORS && parentType == E.FUNCTION_DECL && isNameOf(node, parent)) highlight(element, holder, C.FUNCTION_DECLARATION)
            }
        }
    }

    private fun identifierKey(node: ASTNode, parent: ASTNode, parentType: IElementType): TextAttributesKey? = when {
        isAttributeName(parent) -> C.ATTRIBUTE
        isMacroName(node, parentType) -> C.DIRECTIVE
        parentType == E.FUNCTION_PARAMETER || parentType == E.CLOSURE_PARAMETER -> if (isParameterName(node, parent)) C.PARAMETER else null
        parentType == E.CLOSURE_SHORTHAND_PARAMETER -> C.PARAMETER
        parentType in NAMED_DECLARATIONS && isNameOf(node, parent) -> when (parentType) {
            E.PROTOCOL_DECL -> C.PROTOCOL_NAME
            E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL, E.TYPE_ALIAS_DECL, E.ASSOCIATED_TYPE_DECL -> C.CLASS_NAME
            E.FUNCTION_DECL, E.MACRO_DECL -> C.FUNCTION_DECLARATION
            E.ENUM_CASE_ELEMENT -> C.ENUM_CASE
            E.GENERIC_PARAMETER -> C.TYPE_PARAMETER
            E.IDENTIFIER_PATTERN -> variableKey(parent.psi)
            else -> null
        }
        else -> null
    }

    private fun variableKey(pattern: PsiElement): TextAttributesKey {
        val decl = SwiftDeclarationPresentation.variableDeclarationOf(pattern) ?: return C.LOCAL_VARIABLE
        return when {
            SwiftDeclarationPresentation.isMember(decl) -> C.PROPERTY
            SwiftDeclarationPresentation.isTopLevel(decl) -> C.GLOBAL_VARIABLE
            else -> C.LOCAL_VARIABLE
        }
    }

    /** `\(` (or `\#(`) as one range, and the closing `)`. Annotated on the segment, since `\(` spans two leaves. */
    private fun interpolationDelimiters(segment: ASTNode, holder: AnnotationHolder) {
        val first = segment.firstChildNode ?: return
        if (first.elementType != T.BACKSLASH) return
        val open = segment.findChildByType(T.LPAR)
        val openEnd = open?.textRange?.endOffset ?: first.textRange.endOffset
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(TextRange(first.startOffset, openEnd)).textAttributes(C.VALID_ESCAPE).create()
        val close = segment.lastChildNode
        if (close != null && close.elementType == T.RPAR && close != open) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(close).textAttributes(C.VALID_ESCAPE).create()
        }
    }

    private fun isNameOf(node: ASTNode, parent: ASTNode): Boolean =
        (parent.psi as? SwiftNamedDeclaration)?.nameIdentifier?.node == node

    /** The parameter's own name: the last name before the `:` (the first one is the argument label). */
    private fun isParameterName(node: ASTNode, parent: ASTNode): Boolean {
        var last: ASTNode? = null
        var c = parent.firstChildNode
        while (c != null && c.elementType != T.COLON) {
            if (c.elementType == T.IDENTIFIER || c.elementType == T.WILDCARD) last = c
            c = c.treeNext
        }
        return last == node
    }

    private fun isAttributeName(parent: ASTNode): Boolean =
        parent.elementType == E.IDENTIFIER_TYPE && parent.treeParent?.elementType == E.ATTRIBUTE

    private fun isMacroName(node: ASTNode, parentType: IElementType): Boolean =
        (parentType == E.MACRO_EXPANSION_EXPR || parentType == E.MACRO_EXPANSION_DECL) && node.treePrev?.elementType == T.POUND

    private fun isReservedWord(node: ASTNode): Boolean {
        val chars = node.chars
        return chars.length in 2..MAX_KEYWORD_LENGTH && chars[0] != '`' && SwiftKeywords.reserved(chars) != null
    }

    private fun highlight(element: PsiElement, holder: AnnotationHolder, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }

    private fun erase(element: PsiElement, holder: AnnotationHolder) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).enforcedTextAttributes(TextAttributes.ERASE_MARKER).create()
    }

    companion object {
        private const val MAX_KEYWORD_LENGTH = 15

        private val NAMED_DECLARATIONS = setOf(
            E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL, E.PROTOCOL_DECL, E.TYPE_ALIAS_DECL,
            E.ASSOCIATED_TYPE_DECL, E.FUNCTION_DECL, E.MACRO_DECL, E.ENUM_CASE_ELEMENT, E.GENERIC_PARAMETER,
            E.IDENTIFIER_PATTERN,
        )
    }
}
