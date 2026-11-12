package io.github.alexanderuv.intelliswift.parser

import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/**
 * How strongly a token anchors the surrounding structure during error recovery: a port of
 * swift-syntax's `TokenPrecedence` (`Sources/SwiftParser/TokenPrecedence.swift`). When the parser
 * expects a token that is not there, it may skip tokens of lower precedence to find it, but never
 * skips past a token of equal or higher precedence (a `{`, a `}`, a declaration keyword, ...).
 *
 * Classification uses the raw lexer kinds, as swift-syntax does: contextual keywords are
 * identifiers to the lexer, so they are [IDENTIFIER_LIKE] here too.
 */
internal enum class TokenPrecedence(val rank: Int) {
    UNKNOWN_TOKEN(0),
    IDENTIFIER_LIKE(1),
    EXPR_KEYWORD(2),
    WEAK_BRACKETED(3),
    WEAK_PUNCTUATOR(4),
    MEDIUM_PUNCTUATOR(5),
    WEAK_BRACKET_CLOSE(6),
    STRONG_PUNCTUATOR(7),
    OPENING_BRACE(8),
    STMT_KEYWORD(9),
    DECL_KEYWORD(9),
    CLOSING_BRACE(10),
    OPENING_POUND_IF(11),
    CLOSING_POUND_IF(12),
    ;

    /** Recovery to a token of this precedence may skip over line breaks. */
    val skipsOverNewlines: Boolean get() = rank >= WEAK_BRACKET_CLOSE.rank

    companion object {
        private val IDENTIFIER_LIKE_KEYWORDS = setOf("Self", "false", "nil", "self", "super", "true")
        private val STMT_KEYWORDS = setOf(
            "break", "case", "catch", "continue", "default", "defer", "do", "else", "fallthrough", "for",
            "guard", "if", "in", "repeat", "return", "switch", "throw", "while",
        )
        private val DECL_KEYWORDS = setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
            "inout", "internal", "let", "operator", "precedencegroup", "private", "protocol", "public", "static",
            "struct", "subscript", "typealias", "var",
        )

        /** The precedence of a token of raw lexer kind [type]; `null` is the end of the file. */
        fun of(type: IElementType?): TokenPrecedence {
            if (type is SwiftKeywordTokenType) {
                return when (type.keyword) {
                    in IDENTIFIER_LIKE_KEYWORDS -> IDENTIFIER_LIKE
                    in STMT_KEYWORDS -> STMT_KEYWORD
                    in DECL_KEYWORDS -> DECL_KEYWORD
                    else -> EXPR_KEYWORD
                }
            }
            return when (type) {
                null, T.SEMICOLON, T.ARROW -> STRONG_PUNCTUATOR
                T.BAD_CHARACTER -> UNKNOWN_TOKEN
                T.BINARY_OPERATOR, T.PREFIX_OPERATOR, T.POSTFIX_OPERATOR -> EXPR_KEYWORD
                T.LPAR, T.LBRACKET, T.LANGLE,
                T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE, T.SINGLE_QUOTE, T.RAW_STRING_DELIMITER,
                T.REGEX_SLASH, T.REGEX_POUND_DELIMITER -> WEAK_BRACKETED
                T.INFIX_QUESTION, T.DOT, T.POSTFIX_QUESTION, T.EXCLAMATION, T.BACKSLASH, T.BACKTICK,
                T.ELLIPSIS, T.EQ, T.PREFIX_AMP -> WEAK_PUNCTUATOR
                T.AT, T.COLON, T.COMMA -> MEDIUM_PUNCTUATOR
                T.RANGLE, T.RPAR, T.RBRACKET -> WEAK_BRACKET_CLOSE
                T.LBRACE -> OPENING_BRACE
                T.POUND_IF, T.POUND_ELSEIF, T.POUND_ELSE -> OPENING_POUND_IF
                T.RBRACE -> CLOSING_BRACE
                T.POUND_ENDIF -> CLOSING_POUND_IF
                // Identifiers, literals, `$0`, `_`, `::`, `#`, string and regex contents.
                else -> IDENTIFIER_LIKE
            }
        }

        /** The token that closes a bracketed token of kind [type], if it opens one. */
        fun closingDelimiter(type: IElementType?): IElementType? = when (type) {
            T.LPAR -> T.RPAR
            T.LBRACKET -> T.RBRACKET
            T.LANGLE -> T.RANGLE
            T.LBRACE -> T.RBRACE
            T.POUND_IF, T.POUND_ELSEIF, T.POUND_ELSE -> T.POUND_ENDIF
            T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE, T.SINGLE_QUOTE, T.RAW_STRING_DELIMITER,
            T.REGEX_SLASH, T.REGEX_POUND_DELIMITER -> type
            else -> null
        }
    }
}

/**
 * Whether skipping tokens of lower [precedence] than the token sought reaches a token matching
 * [matches]: swift-syntax's `canRecoverTo`. Returns the number of tokens to skip, or `null` if
 * recovery fails. Bracketed groups are skipped as a whole. Advances this cursor.
 */
internal fun Lookahead.canRecoverTo(
    precedence: TokenPrecedence,
    allowAtStartOfLine: Boolean = true,
    recursionDepth: Int = 1,
    matches: (Lookahead) -> Boolean,
): Int? {
    if (recursionDepth > 10) return null
    val initial = pos
    val skipOverNewlines = precedence.skipsOverNewlines && allowAtStartOfLine
    while (!eof()) {
        if (!skipOverNewlines && atStartOfLine()) break
        if (matches(this)) return pos - initial
        val current = TokenPrecedence.of(type())
        if (current.rank >= precedence.rank) break
        val closing = TokenPrecedence.closingDelimiter(type())
        if (closing == null) {
            bump()
            continue
        }
        // A group that closes on the same line is skipped in one go.
        val sameLine = copy().run {
            var skip = 0
            var result: Int? = null
            while (!eof() && !atStartOfLine()) {
                skip++
                if (at(closing)) { result = skip; break }
                bump()
            }
            result
        }
        if (sameLine != null) {
            bump(sameLine)
            continue
        }
        bump()
        if (canRecoverTo(TokenPrecedence.of(closing), recursionDepth = recursionDepth + 1) { it.at(closing) } == null) continue
        consume(closing)
    }
    return null
}
