package io.github.alexanderuv.intelliswift.psi

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import javax.swing.Icon

/**
 * How declarations are named and drawn in the structure view, breadcrumbs and find usages.
 * Functions are spelled the way Swift refers to them, with argument labels: `move(_:to:)`.
 */
object SwiftDeclarationPresentation {
    /** The name shown for [element], or null if it is not shown as a declaration. */
    fun presentableText(element: PsiElement): String? {
        val node = element.node ?: return null
        return when (node.elementType) {
            E.FUNCTION_DECL, E.MACRO_DECL -> (element as? SwiftNamedDeclaration)?.let { decl ->
                val name = decl.name ?: return null
                val isOperator = decl.nameIdentifier?.node?.elementType != T.IDENTIFIER
                name + labels(node, if (isOperator) Labels.NONE else Labels.FIRST_NAME)
            }
            E.INITIALIZER_DECL -> "init" + initializerOptionality(node) + labels(node, Labels.FIRST_NAME)
            E.DEINITIALIZER_DECL -> "deinit"
            E.SUBSCRIPT_DECL -> "subscript" + labels(node, Labels.EXPLICIT_ONLY)
            E.EXTENSION_DECL -> "extension " + ((element as SwiftExtensionDeclaration).extendedTypeText?.collapseWhitespace() ?: "")
            E.VARIABLE_DECL -> (element as SwiftDeclaration).bindings.mapNotNull { it.name }.joinToString(", ").ifEmpty { null }
            E.ENUM_CASE_DECL -> (element as SwiftDeclaration).bindings.mapNotNull { it.name }.joinToString(", ").ifEmpty { null }
            E.ENUM_CASE_ELEMENT -> (element as? SwiftNamedDeclaration)?.name?.let { name ->
                name + (childOfType(node, E.ENUM_CASE_PARAMETER_CLAUSE)?.text?.collapseWhitespace() ?: "")
            }
            E.IDENTIFIER_PATTERN -> (element as? SwiftNamedDeclaration)?.name?.let { name ->
                val type = typeAnnotation(node)
                if (type != null) "$name: $type" else name
            }
            E.ACCESSOR_DECL -> generateSequence(node.firstChildNode) { it.treeNext }
                .firstOrNull { it.elementType is SwiftKeywordTokenType }?.text
            else -> (element as? SwiftNamedDeclaration)?.name
        }
    }

    /** Extra text shown greyed out next to the name: the inheritance clause of types and extensions. */
    fun locationText(element: PsiElement): String? {
        val node = element.node ?: return null
        if (node.elementType !in E.TYPE_DECLARATIONS && node.elementType != E.EXTENSION_DECL) return null
        val clause = childOfType(node, E.INHERITANCE_CLAUSE) ?: return null
        return clause.text.removePrefix(":").collapseWhitespace().ifEmpty { null }
    }

    fun icon(element: PsiElement): Icon? {
        val node = element.node ?: return null
        return when (node.elementType) {
            E.CLASS_DECL, E.ACTOR_DECL, E.EXTENSION_DECL -> AllIcons.Nodes.Class
            E.STRUCT_DECL -> AllIcons.Nodes.Record
            E.ENUM_DECL -> AllIcons.Nodes.Enum
            E.PROTOCOL_DECL -> AllIcons.Nodes.Interface
            E.TYPE_ALIAS_DECL -> AllIcons.Nodes.Alias
            E.ASSOCIATED_TYPE_DECL, E.GENERIC_PARAMETER -> AllIcons.Nodes.Type
            E.FUNCTION_DECL -> if (isMember(element)) AllIcons.Nodes.Method else AllIcons.Nodes.Function
            E.MACRO_DECL -> AllIcons.Nodes.Function
            E.INITIALIZER_DECL -> AllIcons.Nodes.Constructor
            E.DEINITIALIZER_DECL, E.ACCESSOR_DECL -> AllIcons.Nodes.Method
            E.SUBSCRIPT_DECL -> AllIcons.Nodes.Property
            E.ENUM_CASE_DECL, E.ENUM_CASE_ELEMENT -> AllIcons.Nodes.Field
            E.VARIABLE_DECL -> variableIcon(element)
            E.IDENTIFIER_PATTERN -> variableDeclarationOf(element)?.let(::variableIcon) ?: AllIcons.Nodes.Variable
            else -> null
        }
    }

    /** A short noun for the kind of declaration, as find usages shows it. */
    fun kindName(element: PsiElement): String = when (element.node?.elementType) {
        E.CLASS_DECL -> "class"
        E.STRUCT_DECL -> "struct"
        E.ENUM_DECL -> "enum"
        E.ACTOR_DECL -> "actor"
        E.PROTOCOL_DECL -> "protocol"
        E.EXTENSION_DECL -> "extension"
        E.FUNCTION_DECL -> if (isMember(element)) "method" else "function"
        E.INITIALIZER_DECL -> "initializer"
        E.SUBSCRIPT_DECL -> "subscript"
        E.TYPE_ALIAS_DECL -> "type alias"
        E.ASSOCIATED_TYPE_DECL -> "associated type"
        E.ENUM_CASE_ELEMENT -> "enum case"
        E.OPERATOR_DECL -> "operator"
        E.PRECEDENCE_GROUP_DECL -> "precedence group"
        E.MACRO_DECL -> "macro"
        E.GENERIC_PARAMETER -> "generic parameter"
        E.IDENTIFIER_PATTERN -> {
            val decl = variableDeclarationOf(element)
            when {
                decl == null -> "local variable"
                isMember(decl) -> "property"
                isTopLevel(decl) -> "global variable"
                else -> "local variable"
            }
        }
        else -> "declaration"
    }

    /** True if [element] is declared directly in a type or extension body (looking through `#if`). */
    fun isMember(element: PsiElement): Boolean = container(element)?.node?.elementType == E.MEMBER_BLOCK

    fun isTopLevel(element: PsiElement): Boolean = container(element) is SwiftFile

    private fun container(element: PsiElement): PsiElement? {
        var p = element.parent
        while (p != null) {
            val t = p.node?.elementType
            if (t != E.IF_CONFIG_CLAUSE && t != E.IF_CONFIG_DECL) return p
            p = p.parent
        }
        return null
    }

    /** The `var`/`let` declaration that [pattern] names, if it is one of its bindings. */
    fun variableDeclarationOf(pattern: PsiElement): SwiftVariableDeclaration? {
        var p = pattern.parent
        while (p != null) {
            when (p.node?.elementType) {
                E.PATTERN_BINDING, E.TUPLE_PATTERN, E.TUPLE_PATTERN_ELEMENT, E.VALUE_BINDING_PATTERN -> p = p.parent
                E.VARIABLE_DECL -> return p as? SwiftVariableDeclaration
                else -> return null
            }
        }
        return null
    }

    private fun variableIcon(decl: PsiElement): Icon = when {
        !isMember(decl) -> AllIcons.Nodes.Variable
        decl.node.getChildren(null).any { b -> b.elementType == E.PATTERN_BINDING && childOfType(b, E.ACCESSOR_BLOCK) != null } ->
            AllIcons.Nodes.Property
        else -> AllIcons.Nodes.Field
    }

    /** Where argument labels come from. */
    private enum class Labels {
        /** Functions and initializers: the first name is the label. */
        FIRST_NAME,
        /** Subscripts: no label unless the parameter spells out two names. */
        EXPLICIT_ONLY,
        /** Operators never have argument labels. */
        NONE,
    }

    /** `(_:to:)`: one label per parameter. */
    private fun labels(decl: ASTNode, style: Labels): String {
        val clause = childOfType(childOfType(decl, E.FUNCTION_SIGNATURE) ?: decl, E.PARAMETER_CLAUSE) ?: return "()"
        val labels = clause.getChildren(null).filter { it.elementType == E.FUNCTION_PARAMETER }.map { param ->
            val names = param.getChildren(null).takeWhile { it.elementType != T.COLON }
                .filter { it.elementType == T.IDENTIFIER || it.elementType == T.WILDCARD }
            val label = when {
                style == Labels.NONE -> "_"
                style == Labels.EXPLICIT_ONLY && names.size < 2 -> "_"
                else -> names.firstOrNull()?.text?.removeSurrounding("`") ?: "_"
            }
            "$label:"
        }
        return labels.joinToString("", "(", ")")
    }

    private fun initializerOptionality(decl: ASTNode): String {
        var c = decl.firstChildNode
        while (c != null && c.elementType != io.github.alexanderuv.intelliswift.lexer.KW.INIT) c = c.treeNext
        val next = c?.treeNext?.text
        return if (next == "?" || next == "!") next else ""
    }

    /** The declared type; in `var a, b: Int` the annotation on `b` also types `a`. */
    private fun typeAnnotation(pattern: ASTNode): String? {
        var binding: ASTNode? = pattern.treeParent?.takeIf { it.elementType == E.PATTERN_BINDING } ?: return null
        while (binding != null) {
            childOfType(binding, E.TYPE_ANNOTATION)?.let { return it.text.removePrefix(":").collapseWhitespace().ifEmpty { null } }
            if (childOfType(binding, E.INITIALIZER_CLAUSE) != null || childOfType(binding, E.ACCESSOR_BLOCK) != null) return null
            binding = generateSequence(binding.treeNext) { it.treeNext }.firstOrNull { it.elementType == E.PATTERN_BINDING }
        }
        return null
    }

    private fun childOfType(node: ASTNode, type: com.intellij.psi.tree.IElementType): ASTNode? = node.findChildByType(type)

    private fun String.collapseWhitespace(): String = trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
