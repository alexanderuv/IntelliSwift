package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.highlighting.SwiftHighlighterColors as C

/**
 * Lexer-based highlighting. The lexer only produces reserved keywords; contextual keywords,
 * declaration names and attribute names need the parse tree and come from [SwiftHighlightingAnnotator].
 */
class SwiftSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = SwiftLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(keyFor(tokenType))

    companion object {
        private val SELF_KEYWORDS = setOf("self", "Self", "super")

        private val KEYS: Map<IElementType, TextAttributesKey> = buildMap {
            put(T.IDENTIFIER, C.IDENTIFIER)
            put(T.DOLLAR_IDENTIFIER, C.IDENTIFIER)
            put(T.WILDCARD, C.KEYWORD)
            for (t in T.NUMBER_LITERALS.types) put(t, C.NUMBER)
            put(T.STRING_SEGMENT, C.STRING)
            for (t in T.STRING_DELIMITERS.types) put(t, C.STRING)
            put(T.ESCAPE_SEQUENCE, C.VALID_ESCAPE)
            put(T.INVALID_ESCAPE_SEQUENCE, C.INVALID_ESCAPE)
            for (t in T.REGEX_TOKENS.types) put(t, C.REGEX)
            put(T.LINE_COMMENT, C.LINE_COMMENT)
            put(T.SHEBANG, C.LINE_COMMENT)
            put(T.BLOCK_COMMENT, C.BLOCK_COMMENT)
            put(T.DOC_LINE_COMMENT, C.DOC_COMMENT)
            put(T.DOC_BLOCK_COMMENT, C.DOC_COMMENT)
            for (t in T.OPERATORS.types) put(t, C.OPERATOR)
            put(T.BACKSLASH, C.OPERATOR)
            put(T.LPAR, C.PARENTHESES)
            put(T.RPAR, C.PARENTHESES)
            put(T.LBRACKET, C.BRACKETS)
            put(T.RBRACKET, C.BRACKETS)
            put(T.LBRACE, C.BRACES)
            put(T.RBRACE, C.BRACES)
            put(T.COMMA, C.COMMA)
            put(T.SEMICOLON, C.SEMICOLON)
            put(T.DOT, C.DOT)
            put(T.AT, C.ATTRIBUTE)
            put(T.POUND, C.DIRECTIVE)
            for (t in T.POUND_KEYWORDS.types) put(t, C.DIRECTIVE)
            put(T.BAD_CHARACTER, C.BAD_CHARACTER)
        }

        fun keyFor(tokenType: IElementType?): TextAttributesKey? {
            if (tokenType == null) return null
            if (tokenType is SwiftKeywordTokenType) return if (tokenType.keyword in SELF_KEYWORDS) C.SELF else C.KEYWORD
            return KEYS[tokenType]
        }
    }
}

class SwiftSyntaxHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
    override fun createHighlighter(): SyntaxHighlighter = SwiftSyntaxHighlighter()
}
