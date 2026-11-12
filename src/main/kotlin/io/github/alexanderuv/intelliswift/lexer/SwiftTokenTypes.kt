package io.github.alexanderuv.intelliswift.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage

open class SwiftTokenType(debugName: String) : IElementType(debugName, SwiftLanguage) {
    override fun toString(): String = "SwiftTokenType.${super.toString()}"
}

/**
 * A keyword token. Reserved keywords come straight from the lexer; contextual keywords
 * (`get`, `some`, `async`, ...) are lexed as [SwiftTokenTypes.IDENTIFIER] and remapped to
 * their keyword type by the parser when they are used as keywords, mirroring swift-syntax.
 */
class SwiftKeywordTokenType(val keyword: String, val isReserved: Boolean) : SwiftTokenType(keyword.uppercase() + "_KEYWORD")

/**
 * Token kinds follow swift-syntax's `TokenKind` so that the conformance test can compare token
 * streams. Deliberate differences:
 * - whitespace and comments are tokens (swift-syntax keeps them as trivia);
 * - escape sequences inside string literals are separate [ESCAPE_SEQUENCE] tokens;
 * - an operator that starts with `>` is lexed with each leading `>` as its own token, so the
 *   parser can close generic argument lists (`Array<Array<Int>>`). In expressions the parser
 *   collapses adjacent pieces back into one operator.
 */
object SwiftTokenTypes {
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val LINE_COMMENT = SwiftTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = SwiftTokenType("BLOCK_COMMENT")
    @JvmField val DOC_LINE_COMMENT = SwiftTokenType("DOC_LINE_COMMENT")
    @JvmField val DOC_BLOCK_COMMENT = SwiftTokenType("DOC_BLOCK_COMMENT")
    @JvmField val SHEBANG = SwiftTokenType("SHEBANG")

    @JvmField val IDENTIFIER = SwiftTokenType("IDENTIFIER")
    @JvmField val DOLLAR_IDENTIFIER = SwiftTokenType("DOLLAR_IDENTIFIER")
    @JvmField val WILDCARD = SwiftTokenType("WILDCARD")

    @JvmField val INTEGER_LITERAL = SwiftTokenType("INTEGER_LITERAL")
    @JvmField val FLOAT_LITERAL = SwiftTokenType("FLOAT_LITERAL")

    @JvmField val STRING_QUOTE = SwiftTokenType("STRING_QUOTE")
    @JvmField val MULTILINE_STRING_QUOTE = SwiftTokenType("MULTILINE_STRING_QUOTE")
    @JvmField val SINGLE_QUOTE = SwiftTokenType("SINGLE_QUOTE")
    @JvmField val RAW_STRING_DELIMITER = SwiftTokenType("RAW_STRING_DELIMITER")
    @JvmField val STRING_SEGMENT = SwiftTokenType("STRING_SEGMENT")
    @JvmField val ESCAPE_SEQUENCE = SwiftTokenType("ESCAPE_SEQUENCE")
    @JvmField val INVALID_ESCAPE_SEQUENCE = SwiftTokenType("INVALID_ESCAPE_SEQUENCE")

    @JvmField val REGEX_SLASH = SwiftTokenType("REGEX_SLASH")
    @JvmField val REGEX_POUND_DELIMITER = SwiftTokenType("REGEX_POUND_DELIMITER")
    @JvmField val REGEX_PATTERN = SwiftTokenType("REGEX_PATTERN")

    @JvmField val LPAR = SwiftTokenType("LPAR")
    @JvmField val RPAR = SwiftTokenType("RPAR")
    @JvmField val LBRACE = SwiftTokenType("LBRACE")
    @JvmField val RBRACE = SwiftTokenType("RBRACE")
    @JvmField val LBRACKET = SwiftTokenType("LBRACKET")
    @JvmField val RBRACKET = SwiftTokenType("RBRACKET")
    @JvmField val COMMA = SwiftTokenType("COMMA")
    @JvmField val SEMICOLON = SwiftTokenType("SEMICOLON")
    @JvmField val COLON = SwiftTokenType("COLON")
    @JvmField val COLON_COLON = SwiftTokenType("COLON_COLON")
    @JvmField val AT = SwiftTokenType("AT")
    @JvmField val POUND = SwiftTokenType("POUND")
    @JvmField val BACKTICK = SwiftTokenType("BACKTICK")
    @JvmField val BACKSLASH = SwiftTokenType("BACKSLASH")
    @JvmField val DOT = SwiftTokenType("DOT")
    @JvmField val ARROW = SwiftTokenType("ARROW")
    @JvmField val EQ = SwiftTokenType("EQ")
    @JvmField val PREFIX_AMP = SwiftTokenType("PREFIX_AMP")
    @JvmField val POSTFIX_QUESTION = SwiftTokenType("POSTFIX_QUESTION")
    @JvmField val INFIX_QUESTION = SwiftTokenType("INFIX_QUESTION")
    @JvmField val EXCLAMATION = SwiftTokenType("EXCLAMATION")

    @JvmField val BINARY_OPERATOR = SwiftTokenType("BINARY_OPERATOR")
    @JvmField val PREFIX_OPERATOR = SwiftTokenType("PREFIX_OPERATOR")
    @JvmField val POSTFIX_OPERATOR = SwiftTokenType("POSTFIX_OPERATOR")

    // Produced by the parser only, by remapping operator tokens.
    @JvmField val LANGLE = SwiftTokenType("LANGLE")
    @JvmField val RANGLE = SwiftTokenType("RANGLE")
    @JvmField val ELLIPSIS = SwiftTokenType("ELLIPSIS")

    @JvmField val POUND_IF = SwiftTokenType("POUND_IF")
    @JvmField val POUND_ELSE = SwiftTokenType("POUND_ELSE")
    @JvmField val POUND_ELSEIF = SwiftTokenType("POUND_ELSEIF")
    @JvmField val POUND_ENDIF = SwiftTokenType("POUND_ENDIF")
    @JvmField val POUND_AVAILABLE = SwiftTokenType("POUND_AVAILABLE")
    @JvmField val POUND_UNAVAILABLE = SwiftTokenType("POUND_UNAVAILABLE")
    @JvmField val POUND_SOURCE_LOCATION = SwiftTokenType("POUND_SOURCE_LOCATION")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_LINE_COMMENT, DOC_BLOCK_COMMENT, SHEBANG)
    @JvmField val WHITESPACES = TokenSet.create(WHITE_SPACE)

    @JvmField val STRING_CONTENT = TokenSet.create(STRING_SEGMENT, ESCAPE_SEQUENCE, INVALID_ESCAPE_SEQUENCE)
    @JvmField val STRING_DELIMITERS = TokenSet.create(STRING_QUOTE, MULTILINE_STRING_QUOTE, SINGLE_QUOTE, RAW_STRING_DELIMITER)
    @JvmField val STRING_TOKENS = TokenSet.orSet(STRING_CONTENT, STRING_DELIMITERS)
    @JvmField val REGEX_TOKENS = TokenSet.create(REGEX_SLASH, REGEX_POUND_DELIMITER, REGEX_PATTERN)
    @JvmField val NUMBER_LITERALS = TokenSet.create(INTEGER_LITERAL, FLOAT_LITERAL)

    @JvmField val OPERATORS = TokenSet.create(
        BINARY_OPERATOR, PREFIX_OPERATOR, POSTFIX_OPERATOR, EQ, PREFIX_AMP, POSTFIX_QUESTION, INFIX_QUESTION,
        EXCLAMATION, ARROW, LANGLE, RANGLE, ELLIPSIS,
    )
    @JvmField val POUND_KEYWORDS = TokenSet.create(
        POUND_IF, POUND_ELSE, POUND_ELSEIF, POUND_ENDIF, POUND_AVAILABLE, POUND_UNAVAILABLE, POUND_SOURCE_LOCATION,
    )
}
