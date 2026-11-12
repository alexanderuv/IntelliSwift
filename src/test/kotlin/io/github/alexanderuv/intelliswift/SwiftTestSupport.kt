package io.github.alexanderuv.intelliswift

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/** Shared helpers for comparing our tokens and trees with swift-syntax's. */
object SwiftTestSupport {
    /** All lexer tokens as `(type, text)`, including whitespace and comments. */
    fun lex(text: CharSequence): List<Pair<IElementType, String>> {
        val lexer = SwiftLexer()
        lexer.start(text, 0, text.length, 0)
        val result = ArrayList<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += type to text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            lexer.advance()
        }
        return result
    }

    /** swift-syntax's `TokenKind` name for one of our token types (as printed by the oracle). */
    fun oracleKind(type: IElementType): String = when (type) {
        is SwiftKeywordTokenType -> "keyword:${type.keyword}"
        T.IDENTIFIER -> "identifier"
        T.DOLLAR_IDENTIFIER -> "dollarIdentifier"
        T.WILDCARD -> "wildcard"
        T.INTEGER_LITERAL -> "integerLiteral"
        T.FLOAT_LITERAL -> "floatLiteral"
        T.STRING_QUOTE -> "stringQuote"
        T.MULTILINE_STRING_QUOTE -> "multilineStringQuote"
        T.SINGLE_QUOTE -> "singleQuote"
        T.RAW_STRING_DELIMITER -> "rawStringPoundDelimiter"
        T.STRING_SEGMENT, T.ESCAPE_SEQUENCE, T.INVALID_ESCAPE_SEQUENCE -> "stringSegment"
        T.REGEX_SLASH -> "regexSlash"
        T.REGEX_POUND_DELIMITER -> "regexPoundDelimiter"
        T.REGEX_PATTERN -> "regexLiteralPattern"
        T.LPAR -> "leftParen"
        T.RPAR -> "rightParen"
        T.LBRACE -> "leftBrace"
        T.RBRACE -> "rightBrace"
        T.LBRACKET -> "leftSquare"
        T.RBRACKET -> "rightSquare"
        T.COMMA -> "comma"
        T.SEMICOLON -> "semicolon"
        T.COLON -> "colon"
        T.COLON_COLON -> "colonColon"
        T.AT -> "atSign"
        T.POUND -> "pound"
        T.BACKTICK -> "backtick"
        T.BACKSLASH -> "backslash"
        T.DOT -> "period"
        T.ARROW -> "arrow"
        T.EQ -> "equal"
        T.PREFIX_AMP -> "prefixAmpersand"
        T.POSTFIX_QUESTION -> "postfixQuestionMark"
        T.INFIX_QUESTION -> "infixQuestionMark"
        T.EXCLAMATION -> "exclamationMark"
        T.BINARY_OPERATOR -> "binaryOperator"
        T.PREFIX_OPERATOR -> "prefixOperator"
        T.POSTFIX_OPERATOR -> "postfixOperator"
        T.LANGLE -> "leftAngle"
        T.RANGLE -> "rightAngle"
        T.ELLIPSIS -> "ellipsis"
        T.POUND_IF -> "poundIf"
        T.POUND_ELSE -> "poundElse"
        T.POUND_ELSEIF -> "poundElseif"
        T.POUND_ENDIF -> "poundEndif"
        T.POUND_AVAILABLE -> "poundAvailable"
        T.POUND_UNAVAILABLE -> "poundUnavailable"
        T.POUND_SOURCE_LOCATION -> "poundSourceLocation"
        T.SHEBANG -> "shebang"
        TokenType.BAD_CHARACTER -> "unknown"
        else -> "?$type"
    }

    /** The significant leaves of a parsed tree, in order. */
    fun leaves(root: ASTNode): List<ASTNode> {
        val out = ArrayList<ASTNode>()
        fun walk(n: ASTNode) {
            var c = n.firstChildNode
            if (c == null) {
                val t = n.elementType
                if (t != T.WHITE_SPACE && t !in T.COMMENTS && n.textLength > 0) out += n
                return
            }
            while (c != null) { walk(c); c = c.treeNext }
        }
        walk(root)
        return out
    }

    fun errors(root: ASTNode): List<PsiErrorElement> {
        val out = ArrayList<PsiErrorElement>()
        fun walk(n: ASTNode) {
            (n.psi as? PsiErrorElement)?.let { out += it }
            var c = n.firstChildNode
            while (c != null) { walk(c); c = c.treeNext }
        }
        walk(root)
        return out
    }

    /** A declaration skeleton node in the oracle's format. */
    data class Decl(val kind: String, val name: String?, val children: List<Decl>) {
        fun render(indent: String = ""): String = buildString {
            append(indent).append(kind)
            if (name != null) append(' ').append(name)
            append('\n')
            for (c in children) append(c.render("$indent  "))
        }
    }

    private val DECL_KINDS = mapOf(
        E.IMPORT_DECL to "ImportDecl", E.CLASS_DECL to "ClassDecl", E.STRUCT_DECL to "StructDecl",
        E.ENUM_DECL to "EnumDecl", E.ACTOR_DECL to "ActorDecl", E.PROTOCOL_DECL to "ProtocolDecl",
        E.EXTENSION_DECL to "ExtensionDecl", E.FUNCTION_DECL to "FunctionDecl", E.INITIALIZER_DECL to "InitializerDecl",
        E.DEINITIALIZER_DECL to "DeinitializerDecl", E.SUBSCRIPT_DECL to "SubscriptDecl", E.VARIABLE_DECL to "VariableDecl",
        E.TYPE_ALIAS_DECL to "TypeAliasDecl", E.ASSOCIATED_TYPE_DECL to "AssociatedTypeDecl",
        E.ENUM_CASE_DECL to "EnumCaseDecl", E.OPERATOR_DECL to "OperatorDecl",
        E.PRECEDENCE_GROUP_DECL to "PrecedenceGroupDecl", E.MACRO_DECL to "MacroDecl",
        E.MACRO_EXPANSION_DECL to "MacroExpansionDecl", E.IF_CONFIG_DECL to "IfConfigDecl",
        E.POUND_SOURCE_LOCATION to "PoundSourceLocation", E.ACCESSOR_DECL to "AccessorDecl",
        E.MISSING_DECL to "MissingDecl", E.EDITOR_PLACEHOLDER_DECL to "EditorPlaceholderDecl",
    )

    fun decls(root: ASTNode): List<Decl> {
        val out = ArrayList<Decl>()
        var c = root.firstChildNode
        while (c != null) {
            val kind = DECL_KINDS[c.elementType]
            if (kind != null) out += Decl(kind, declName(c), decls(c)) else out += decls(c)
            c = c.treeNext
        }
        return out
    }

    private fun childrenOf(n: ASTNode): List<ASTNode> = generateSequence(n.firstChildNode) { it.treeNext }.toList()

    private fun significant(n: ASTNode) = n.elementType != T.WHITE_SPACE && n.elementType !in T.COMMENTS

    private fun noSpace(s: String) = s.filterNot { it.isWhitespace() }

    private fun declName(n: ASTNode): String? {
        val kids = childrenOf(n).filter(::significant)
        fun tokenAfterKeyword(vararg kws: String): String? {
            val i = kids.indexOfFirst { (it.elementType as? SwiftKeywordTokenType)?.keyword in kws }
            if (i < 0 || i + 1 >= kids.size) return null
            val next = kids[i + 1]
            if (next.firstChildNode != null) return null
            return next.text
        }
        return when (n.elementType) {
            E.CLASS_DECL -> tokenAfterKeyword("class")
            E.STRUCT_DECL -> tokenAfterKeyword("struct")
            E.ENUM_DECL -> tokenAfterKeyword("enum")
            E.ACTOR_DECL -> tokenAfterKeyword("actor")
            E.PROTOCOL_DECL -> tokenAfterKeyword("protocol")
            E.FUNCTION_DECL -> tokenAfterKeyword("func")
            E.TYPE_ALIAS_DECL -> tokenAfterKeyword("typealias")
            E.ASSOCIATED_TYPE_DECL -> tokenAfterKeyword("associatedtype")
            E.OPERATOR_DECL -> tokenAfterKeyword("operator")
            E.PRECEDENCE_GROUP_DECL -> tokenAfterKeyword("precedencegroup")
            E.MACRO_DECL -> tokenAfterKeyword("macro")
            E.MACRO_EXPANSION_DECL -> kids.getOrNull(kids.indexOfFirst { it.elementType == T.POUND } + 1)?.text
            E.ACCESSOR_DECL -> kids.firstOrNull { it.elementType is SwiftKeywordTokenType && (it.elementType as SwiftKeywordTokenType).keyword in ACCESSORS }?.text
            E.IMPORT_DECL -> kids.firstOrNull { it.elementType == E.IMPORT_PATH }?.text?.let(::noSpace)
            E.EXTENSION_DECL -> kids.getOrNull(kids.indexOfFirst { it.elementType == io.github.alexanderuv.intelliswift.lexer.KW.EXTENSION } + 1)?.text?.trim()
            E.ENUM_CASE_DECL -> kids.filter { it.elementType == E.ENUM_CASE_ELEMENT }
                .map { el -> childrenOf(el).firstOrNull(::significant)?.text ?: return null }
                .joinToString(",").ifEmpty { null }
            E.VARIABLE_DECL -> {
                val names = kids.filter { it.elementType == E.PATTERN_BINDING }.map { b ->
                    val pattern = childrenOf(b).firstOrNull(::significant) ?: return null
                    if (pattern.elementType != E.IDENTIFIER_PATTERN) return null
                    pattern.text
                }
                names.joinToString(",").ifEmpty { null }
            }
            else -> null
        }
    }

    private val ACCESSORS = setOf(
        "get", "set", "willSet", "didSet", "_read", "_modify", "read", "modify", "unsafeAddress",
        "unsafeMutableAddress", "init", "borrow", "mutate", "addressWithOwner", "addressWithNativeOwner",
        "mutableAddressWithOwner", "mutableAddressWithNativeOwner",
    )

    /**
     * Normalizes a `(kind, text)` token stream for comparison: string literal segments differ in
     * how whitespace and escapes are split between tokens and trivia, so consecutive segments are
     * merged and their whitespace removed.
     */
    fun normalize(tokens: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((kind, text) in tokens) {
            if (kind == "stringSegment") {
                val stripped = text.filterNot { it.isWhitespace() }
                val last = out.lastOrNull()
                if (last != null && last.first == "stringSegment") {
                    out[out.size - 1] = kind to (last.second + stripped)
                } else {
                    out += kind to stripped
                }
                continue
            }
            out += kind to text
        }
        return mergeVersionNumbers(out.filterNot { it.first == "stringSegment" && it.second.isEmpty() })
    }

    /**
     * swift-syntax splits version tuples such as `10.15.1` in availability arguments into
     * `10`, `.`, `15`, ...; we keep the lexer's `10.15` float. Both sides are normalized to one
     * `number` token per dotted run of numbers (and per float literal).
     */
    private fun mergeVersionNumbers(tokens: List<Pair<String, String>>): List<Pair<String, String>> {
        fun isNumber(i: Int) = i < tokens.size && (tokens[i].first == "integerLiteral" || tokens[i].first == "floatLiteral")
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < tokens.size) {
            if (!isNumber(i)) { out += tokens[i]; i++; continue }
            val sb = StringBuilder(tokens[i].second)
            var float = tokens[i].first == "floatLiteral"
            var j = i + 1
            while (j + 1 < tokens.size && tokens[j].first == "period" && isNumber(j + 1)) {
                sb.append('.').append(tokens[j + 1].second)
                float = true
                j += 2
            }
            out += (if (float) "number" else "integerLiteral") to sb.toString()
            i = j
        }
        return out
    }
}
