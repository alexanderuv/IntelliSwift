package io.github.alexanderuv.intelliswift.editor

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/**
 * Auto-closes and types over `"`.
 *
 * The lexer emits the opening and the closing quote of a string as separate one-character
 * [T.STRING_QUOTE] tokens, so the base class, which assumes one token per literal, would see every
 * quote as both opening and closing, and every literal as unterminated. A quote is closing here
 * when it follows string content (or the opening quote of an empty string).
 */
class SwiftQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE) {
    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType !in myLiteralTokenSet) return false
        if (offset != iterator.end - 1) return false
        return isAfterStringContent(iterator)
    }

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType !in myLiteralTokenSet) return false
        if (offset != iterator.start) return false
        return !isAfterStringContent(iterator)
    }

    /** Whether the literal opened by the quote at [iterator] has no closing quote on its line. */
    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean {
        val quote = iterator.tokenType
        if (quote == T.MULTILINE_STRING_QUOTE) return true
        val start = iterator.start
        val chars = editor.document.charsSequence
        try {
            iterator.advance()
            while (!iterator.atEnd()) {
                val t = iterator.tokenType
                if (t == T.STRING_QUOTE) return false
                if (t !in T.STRING_CONTENT) return true
                if (containsLineBreak(chars, iterator.start, iterator.end)) return true
                iterator.advance()
            }
            return true
        } finally {
            while (iterator.atEnd() || iterator.start != start) iterator.retreat()
        }
    }

    private fun isAfterStringContent(iterator: HighlighterIterator): Boolean {
        if (iterator.start == 0) return false
        iterator.retreat()
        try {
            if (iterator.atEnd()) return false
            val previous = iterator.tokenType
            // `\(x)"`: the interpolation's `)` is string-coloured context too.
            return previous in T.STRING_CONTENT || previous in myLiteralTokenSet || previous == T.RPAR && isInsideString(iterator)
        } finally {
            iterator.advance()
        }
    }

    /** True if the `)` at [iterator] closes a string interpolation: some `\` `(` before it on the line opened inside a string. */
    private fun isInsideString(iterator: HighlighterIterator): Boolean {
        var depth = 0
        var steps = 0
        try {
            while (!iterator.atEnd() && steps < MAX_LOOKBEHIND) {
                when (iterator.tokenType) {
                    T.RPAR -> depth++
                    T.LPAR -> {
                        depth--
                        if (depth == 0) {
                            iterator.retreat()
                            steps++
                            return !iterator.atEnd() && iterator.tokenType == T.BACKSLASH
                        }
                    }
                }
                iterator.retreat()
                steps++
            }
            return false
        } finally {
            repeat(steps) { iterator.advance() }
        }
    }

    private fun containsLineBreak(chars: CharSequence, start: Int, end: Int): Boolean {
        for (i in start until end) if (chars[i] == '\n' || chars[i] == '\r') return true
        return false
    }

    private companion object {
        const val MAX_LOOKBEHIND = 200
    }
}
