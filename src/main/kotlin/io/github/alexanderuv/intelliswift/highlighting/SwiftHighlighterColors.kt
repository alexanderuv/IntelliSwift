package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/** Swift text attribute keys. Each falls back to a language-neutral default, so every color scheme covers Swift. */
object SwiftHighlighterColors {
    private fun key(name: String, fallback: TextAttributesKey) = TextAttributesKey.createTextAttributesKey("SWIFT_$name", fallback)

    // Lexer-based
    @JvmField val KEYWORD = key("KEYWORD", D.KEYWORD)
    @JvmField val SELF = key("SELF", D.KEYWORD)
    @JvmField val IDENTIFIER = key("IDENTIFIER", D.IDENTIFIER)
    @JvmField val NUMBER = key("NUMBER", D.NUMBER)
    @JvmField val STRING = key("STRING", D.STRING)
    @JvmField val VALID_ESCAPE = key("VALID_STRING_ESCAPE", D.VALID_STRING_ESCAPE)
    @JvmField val INVALID_ESCAPE = key("INVALID_STRING_ESCAPE", D.INVALID_STRING_ESCAPE)
    @JvmField val REGEX = key("REGEX", D.STRING)
    @JvmField val LINE_COMMENT = key("LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = key("BLOCK_COMMENT", D.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT = key("DOC_COMMENT", D.DOC_COMMENT)
    @JvmField val OPERATOR = key("OPERATOR", D.OPERATION_SIGN)
    @JvmField val PARENTHESES = key("PARENTHESES", D.PARENTHESES)
    @JvmField val BRACKETS = key("BRACKETS", D.BRACKETS)
    @JvmField val BRACES = key("BRACES", D.BRACES)
    @JvmField val COMMA = key("COMMA", D.COMMA)
    @JvmField val SEMICOLON = key("SEMICOLON", D.SEMICOLON)
    @JvmField val DOT = key("DOT", D.DOT)
    @JvmField val ATTRIBUTE = key("ATTRIBUTE", D.METADATA)
    @JvmField val DIRECTIVE = key("DIRECTIVE", D.KEYWORD)
    @JvmField val BAD_CHARACTER = key("BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // Annotator-based
    @JvmField val CLASS_NAME = key("TYPE_DECLARATION", D.CLASS_NAME)
    @JvmField val PROTOCOL_NAME = key("PROTOCOL_DECLARATION", D.INTERFACE_NAME)
    @JvmField val TYPE_PARAMETER = key("TYPE_PARAMETER", D.CLASS_NAME)
    @JvmField val FUNCTION_DECLARATION = key("FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val PARAMETER = key("PARAMETER", D.PARAMETER)
    @JvmField val PROPERTY = key("PROPERTY", D.INSTANCE_FIELD)
    @JvmField val GLOBAL_VARIABLE = key("GLOBAL_VARIABLE", D.GLOBAL_VARIABLE)
    @JvmField val LOCAL_VARIABLE = key("LOCAL_VARIABLE", D.LOCAL_VARIABLE)
    @JvmField val ENUM_CASE = key("ENUM_CASE", D.STATIC_FIELD)
}
