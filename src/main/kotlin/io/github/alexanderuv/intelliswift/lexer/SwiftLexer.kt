package io.github.alexanderuv.intelliswift.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/**
 * The Swift lexer, a port of swift-syntax's `Lexer.Cursor` (`Sources/SwiftParser/Lexer/Cursor.swift`)
 * and `RegexLiteralLexer.swift`, adapted to IntelliJ's model where whitespace and comments are tokens.
 *
 * swift-syntax drives string literals, string interpolation and regex literals with a stack of lexer
 * states; so does this lexer. [getState] is `0` only when that stack is empty and the previous token
 * leaves the lexer in its default context, so the editor highlighter can restart lexing at any token
 * whose state is `0`. See [PrevClass] for the context swift-syntax keeps about the previous token.
 */
class SwiftLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var pos = 0

    private var tokenType: IElementType? = null
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenState = 0

    private val stack = ArrayList<State>()
    private var prev = PrevClass.DEFAULT
    private var prevTokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.pos = startOffset
        stack.clear()
        prevTokenType = null
        prev = PrevClass.entries.getOrNull(initialState) ?: PrevClass.DEFAULT
        if (prev == PrevClass.PREFER_REGEX) {
            prev = PrevClass.EXPR_END
            stack.add(State.PreferRegex)
        }
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    /**
     * Whether the current token continues the operator before it: the lexer splits a leading `<` or
     * `>` off operators (see [lexOperator]), and the parser merges such pieces back unless it uses the
     * first one as an angle bracket.
     */
    var continuesOperator: Boolean = false
        private set

    override fun advance() {
        tokenState = encodeState()
        tokenStart = pos
        continuesOperator = false
        val type = lexToken()
        tokenType = type
        tokenEnd = pos
        if (type != null && type != T.WHITE_SPACE && type !in T.COMMENTS) {
            prevTokenType = type
            prev = PrevClass.of(type)
        }
    }

    private fun encodeState(): Int = when {
        stack.isEmpty() -> prev.ordinal
        stack.size == 1 && stack[0] == State.PreferRegex -> PrevClass.PREFER_REGEX.ordinal
        else -> NESTED_STATE
    }

    // region Lexer states

    private enum class StringKind { SINGLE_LINE, MULTI_LINE, SINGLE_QUOTE }

    private sealed class State {
        data object PreferRegex : State()
        data class AfterRawStringDelimiter(val delimiterLength: Int) : State()
        data class InStringLiteral(val kind: StringKind, val delimiterLength: Int, val atOpening: Boolean) : State()
        data class AfterStringLiteral(val kind: StringKind, val isRawString: Boolean) : State()
        data object AfterClosingStringQuote : State()
        data class InStringInterpolationStart(val kind: StringKind) : State()
        data class InStringInterpolation(val kind: StringKind, val parenCount: Int) : State()
        class InRegexLiteral(val lexemes: List<RegexLexeme>, val index: Int) : State()
        /** Emitting the remaining pieces of an operator that ends at [end]. */
        data class InSplitOperator(val end: Int) : State()
    }

    private class RegexLexeme(val type: IElementType, val end: Int)

    private fun top(): State? = stack.lastOrNull()
    private fun push(s: State) { stack.add(s) }
    private fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    private fun replace(s: State) { pop(); push(s) }

    // endregion

    // region Character access

    private fun at(offset: Int = 0): Int {
        val i = pos + offset
        return if (i < bufferEnd) buffer[i].code else EOF
    }

    private fun charAt(i: Int): Int = if (i in 0 until bufferEnd) buffer[i].code else EOF

    private fun codePointAt(i: Int): Int {
        if (i >= bufferEnd) return EOF
        val c = buffer[i]
        if (Character.isHighSurrogate(c) && i + 1 < bufferEnd && Character.isLowSurrogate(buffer[i + 1])) {
            return Character.toCodePoint(c, buffer[i + 1])
        }
        return c.code
    }

    private fun advanceCodePoint() {
        val cp = codePointAt(pos)
        pos += if (cp > 0xFFFF) 2 else 1
    }

    private fun advanceIf(predicate: (Int) -> Boolean): Boolean {
        val cp = codePointAt(pos)
        if (cp == EOF || !predicate(cp)) return false
        pos += if (cp > 0xFFFF) 2 else 1
        return true
    }

    private fun advanceWhile(predicate: (Int) -> Boolean) {
        while (advanceIf(predicate)) Unit
    }

    private fun advanceMatching(ch: Char): Boolean {
        if (at() == ch.code) { pos++; return true }
        return false
    }

    private fun isAt(vararg chars: Char): Boolean {
        val c = at()
        return chars.any { it.code == c }
    }

    private fun startsWith(s: String, from: Int = pos): Boolean {
        if (from + s.length > bufferEnd) return false
        for (k in s.indices) if (buffer[from + k] != s[k]) return false
        return true
    }

    private fun advanceToEndOfLine() {
        while (pos < bufferEnd && buffer[pos] != '\n' && buffer[pos] != '\r') pos++
    }

    // endregion

    // region Main dispatch

    private fun lexToken(): IElementType? {
        while (true) {
            if (pos >= bufferEnd) return null
            when (val state = top()) {
                null, State.PreferRegex -> {
                    lexTrivia(allowNewlines = true)?.let { return it }
                    val preferRegex = state == State.PreferRegex
                    if (preferRegex) pop()
                    return lexNormal(preferRegex)
                }
                is State.AfterRawStringDelimiter -> {
                    if (isAt('"', '\'')) return lexStringQuote(matchingOpening = null, leadingDelimiterLength = state.delimiterLength)
                    pop()
                }
                is State.InStringLiteral -> {
                    lexInStringLiteral(state)?.let { return it }
                }
                is State.AfterStringLiteral -> {
                    if (isAt('"', '\'')) return lexStringQuote(matchingOpening = state.kind, leadingDelimiterLength = 0)
                    pop()
                }
                State.AfterClosingStringQuote -> {
                    pop()
                    if (at() == '#'.code) {
                        while (advanceMatching('#')) Unit
                        return T.RAW_STRING_DELIMITER
                    }
                }
                is State.InStringInterpolationStart -> when (at()) {
                    '\\'.code -> { pos++; return T.BACKSLASH }
                    '#'.code -> { while (advanceMatching('#')) Unit; return T.RAW_STRING_DELIMITER }
                    '('.code -> {
                        pos++
                        replace(State.InStringInterpolation(state.kind, 0))
                        return T.LPAR
                    }
                    else -> pop()
                }
                is State.InStringInterpolation -> {
                    lexTrivia(allowNewlines = state.kind == StringKind.MULTI_LINE)?.let { return it }
                    when (at()) {
                        '('.code -> {
                            pos++
                            replace(state.copy(parenCount = state.parenCount + 1))
                            return T.LPAR
                        }
                        ')'.code -> {
                            pos++
                            if (state.parenCount == 0) pop() else replace(state.copy(parenCount = state.parenCount - 1))
                            return T.RPAR
                        }
                        '\n'.code, '\r'.code -> {
                            // A single-line string cannot continue onto the next line: leave the
                            // interpolation; the string literal state below then ends at the newline.
                            pop()
                        }
                        else -> return lexNormal(preferRegex = false)
                    }
                }
                is State.InRegexLiteral -> {
                    val lexeme = state.lexemes[state.index]
                    pos = lexeme.end
                    if (state.index + 1 >= state.lexemes.size) pop() else replace(State.InRegexLiteral(state.lexemes, state.index + 1))
                    return lexeme.type
                }
                is State.InSplitOperator -> {
                    val start = pos
                    pos = operatorPieceEnd(start, state.end)
                    if (pos >= state.end) pop()
                    continuesOperator = true
                    return classifyOperator(start, pos)
                }
            }
        }
    }

    /** Lexes whitespace or a comment at the cursor, or returns null if there is none. */
    private fun lexTrivia(allowNewlines: Boolean): IElementType? {
        val start = pos
        while (pos < bufferEnd) {
            val c = buffer[pos]
            when {
                c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '﻿' || c == ' ' -> pos++
                (c == '\n' || c == '\r') && allowNewlines -> pos++
                else -> break
            }
        }
        if (pos > start) return T.WHITE_SPACE

        if (at() == '/'.code && at(1) == '/'.code) {
            val isDoc = at(2) == '/'.code && at(3) != '/'.code
            advanceToEndOfLine()
            return if (isDoc) T.DOC_LINE_COMMENT else T.LINE_COMMENT
        }
        if (at() == '/'.code && at(1) == '*'.code) {
            val isDoc = at(2) == '*'.code && at(3) != '*'.code && at(3) != '/'.code
            pos += 2
            var depth = 1
            while (pos < bufferEnd && depth > 0) {
                if (buffer[pos] == '*' && charAt(pos + 1) == '/'.code) { depth--; pos += 2 }
                else if (buffer[pos] == '/' && charAt(pos + 1) == '*'.code) { depth++; pos += 2 }
                else pos++
            }
            return if (isDoc) T.DOC_BLOCK_COMMENT else T.BLOCK_COMMENT
        }
        if ((at() == '<'.code || at() == '>'.code) && tryLexConflictMarker()) return T.LINE_COMMENT
        return null
    }

    private fun lexNormal(preferRegex: Boolean): IElementType {
        when (val c = at()) {
            '@'.code -> { pos++; return T.AT }
            '{'.code -> { pos++; return T.LBRACE }
            '['.code -> { pos++; return T.LBRACKET }
            '('.code -> { pos++; return T.LPAR }
            '}'.code -> { pos++; return T.RBRACE }
            ']'.code -> { pos++; return T.RBRACKET }
            ')'.code -> { pos++; return T.RPAR }
            ','.code -> { pos++; return T.COMMA }
            ';'.code -> { pos++; return T.SEMICOLON }
            '\\'.code -> { pos++; return T.BACKSLASH }
            ':'.code -> {
                pos++
                return if (advanceMatching(':')) T.COLON_COLON else T.COLON
            }
            '#'.code -> return lexPound()
            '!'.code, '?'.code -> {
                lexPostfixOptionalChain()?.let { return it }
                return lexOperator(preferRegex)
            }
            '<'.code -> {
                if (at(1) == '#'.code && tryLexEditorPlaceholder()) return T.IDENTIFIER
                return lexOperator(preferRegex)
            }
            '$'.code -> return lexDollarIdentifier()
            '"'.code, '\''.code -> return lexStringQuote(matchingOpening = null, leadingDelimiterLength = 0)
            '`'.code -> return lexEscapedIdentifier()
            else -> {
                if (CharacterInfo.isDigit(c)) return lexNumber()
                val cp = codePointAt(pos)
                if (CharacterInfo.isIdentifierStart(cp)) return lexIdentifier()
                if (CharacterInfo.isOperatorStart(cp)) return lexOperator(preferRegex)
                return lexUnknown()
            }
        }
    }

    // endregion

    // region Operators

    private fun isLeftBound(tokStart: Int): Boolean {
        if (tokStart <= 0) return false
        return when (buffer[tokStart - 1]) {
            ' ', '\r', '\n', '\t', '(', '[', '{', ',', ';', ':', '\u0000', ' ' -> false
            '/' -> !(tokStart >= 2 && buffer[tokStart - 2] == '*')
            else -> true
        }
    }

    private fun isRightBound(tokEnd: Int, isLeftBound: Boolean): Boolean {
        if (tokEnd >= bufferEnd) return false
        return when (buffer[tokEnd]) {
            ' ', '\r', '\n', '\t', ')', ']', '}', ',', ';', ':', '\u0000', ' ' -> false
            '.' -> !isLeftBound
            '/' -> !(charAt(tokEnd + 1) == '/'.code || charAt(tokEnd + 1) == '*'.code)
            else -> true
        }
    }

    private fun lexPostfixOptionalChain(): IElementType? {
        if (!isLeftBound(pos)) return null
        if (prev == PrevClass.TRY) push(State.PreferRegex)
        val type = if (at() == '!'.code) T.EXCLAMATION else T.POSTFIX_QUESTION
        pos++
        return type
    }

    private fun classifyOperator(start: Int, end: Int): IElementType {
        val leftBound = isLeftBound(start)
        val rightBound = isRightBound(end, leftBound)
        val len = end - start
        if (len == 1) {
            when (buffer[start]) {
                '=' -> return T.EQ
                '&' -> if (!leftBound && rightBound) return T.PREFIX_AMP
                '.' -> return T.DOT
                '?' -> return if (leftBound) T.POSTFIX_QUESTION else T.INFIX_QUESTION
                '!' -> if (leftBound) return T.EXCLAMATION
            }
        } else if (len == 2) {
            if (buffer[start] == '-' && buffer[start + 1] == '>') return T.ARROW
            if (buffer[start] == '*' && buffer[start + 1] == '/') return T.BAD_CHARACTER
        } else if (buffer.subSequence(start, end).contains("*/")) {
            return T.BAD_CHARACTER
        }
        return when {
            leftBound == rightBound -> T.BINARY_OPERATOR
            leftBound -> T.POSTFIX_OPERATOR
            else -> T.PREFIX_OPERATOR
        }
    }

    private fun lexOperator(preferRegex: Boolean): IElementType {
        val start = pos
        advanceCodePoint()
        val startsWithDot = buffer[start] == '.'
        while (true) {
            if (at() == '.'.code && !startsWithDot) break
            if (startsWith("<#") && containsPlaceholderEnd(pos)) break
            if (!advanceIf(CharacterInfo::isOperatorContinuation)) break
        }

        // A regex literal may start inside the operator, e.g. `x = /abc/` or `foo(!/x/)`.
        var slash = start
        while (slash < pos && buffer[slash] != '/') slash++
        if (slash < pos) {
            val lexemes = tryScanOperatorAsRegex(slash, start, pos, preferRegex)
            if (lexemes != null) {
                if (slash > start) {
                    push(State.InRegexLiteral(lexemes, 0))
                    return splitOperator(start, slash)
                }
                return emitRegexLexemes(lexemes)
            }
        }

        if (pos - start > 2) {
            // A `//` or `/*` inside an operator starts a comment.
            for (p in start + 1 until pos) {
                if (buffer[p] == '/' && (charAt(p + 1) == '/'.code || charAt(p + 1) == '*'.code)) {
                    pos = p
                    break
                }
            }
        }

        return splitOperator(start, pos)
    }

    /**
     * Emits the first piece of the operator [start]..[end] and queues the rest. swift-syntax's parser
     * consumes a leading `<` or `>` of an operator token as an angle bracket where generic clauses
     * open or close (`Vector<-1>`, `Array<Array<Int>>`); PsiBuilder cannot split tokens, so the lexer
     * splits them and the parser merges the pieces back (see [continuesOperator]). The operator's
     * extent is decided once, for the whole operator, so the pieces never re-run regex or comment
     * detection.
     *
     * When swift-syntax splits a token it re-lexes the remainder, so the pieces follow the lexer's
     * rules for a token starting there: a left-bound `?` or `!` is a token of its own
     * (`UnsafePointer<CChar>?>` closes with `>`, `?`, `>`).
     */
    private fun splitOperator(start: Int, end: Int): IElementType {
        pos = operatorPieceEnd(start, end)
        if (pos < end) push(State.InSplitOperator(end))
        return classifyOperator(start, pos)
    }

    private fun operatorPieceEnd(start: Int, end: Int): Int = when (buffer[start]) {
        '<', '>' -> if (end - start > 1) start + 1 else end
        '?', '!' -> if (isLeftBound(start)) start + 1 else end
        else -> end
    }

    private fun containsPlaceholderEnd(from: Int): Boolean {
        var i = from + 2
        while (i + 1 < bufferEnd) {
            val c = buffer[i]
            if (c == '\n') return false
            if (c == '<' && buffer[i + 1] == '#') return false
            if (c == '#' && buffer[i + 1] == '>') return true
            i++
        }
        return false
    }

    private fun tryLexEditorPlaceholder(): Boolean {
        var p = pos + 2
        while (p < bufferEnd) {
            val c = buffer[p++]
            if (c == '\n') return false
            if (c == '<' && charAt(p) == '#'.code) return false
            if (c == '#' && charAt(p) == '>'.code) {
                pos = p + 1
                return true
            }
        }
        return false
    }

    // endregion

    // region Identifiers and keywords

    private fun lexIdentifier(): IElementType {
        val start = pos
        advanceCodePoint()
        advanceWhile(CharacterInfo::isIdentifierContinuation)
        val text = buffer.subSequence(start, pos)
        SwiftKeywords.reserved(text)?.let { return it }
        if (text.length == 1 && text[0] == '_') return T.WILDCARD
        return T.IDENTIFIER
    }

    private fun lexEscapedIdentifier(): IElementType {
        val quote = pos
        pos++
        var isEmpty = true
        while (pos < bufferEnd) {
            val c = buffer[pos]
            if (c == '`' || c == '\n' || c == '\r') break
            advanceCodePoint()
            isEmpty = false
        }
        if (advanceMatching('`')) {
            return if (isEmpty) T.BAD_CHARACTER else T.IDENTIFIER
        }
        pos = quote + 1
        return T.BACKTICK
    }

    private fun lexDollarIdentifier(): IElementType {
        val start = pos
        pos++
        var allDigits = true
        while (true) {
            val cp = codePointAt(pos)
            if (CharacterInfo.isDigit(cp)) { pos++; continue }
            if (cp != EOF && CharacterInfo.isIdentifierContinuation(cp)) { advanceCodePoint(); allDigits = false; continue }
            break
        }
        if (pos == start + 1) return T.IDENTIFIER
        return if (allDigits) T.DOLLAR_IDENTIFIER else T.IDENTIFIER
    }

    private fun lexUnknown(): IElementType {
        val cp = codePointAt(pos)
        if (CharacterInfo.isIdentifierContinuation(cp)) {
            // An identifier that starts with a character that is only valid inside identifiers.
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.IDENTIFIER
        }
        if (cp == 0x201C) {
            // An opening curly quote: swift-syntax lexes the "string" as one identifier and diagnoses it.
            advanceCodePoint()
            val save = pos
            while (pos < bufferEnd && buffer[pos] != '\n' && buffer[pos] != '\r') {
                if (codePointAt(pos) == 0x201D || buffer[pos] == '"') { pos++; return T.BAD_CHARACTER }
                advanceCodePoint()
            }
            pos = save
            return T.BAD_CHARACTER
        }
        advanceCodePoint()
        return T.BAD_CHARACTER
    }

    private fun tryLexConflictMarker(): Boolean {
        if (!(pos == 0 || buffer[pos - 1] == '\n' || buffer[pos - 1] == '\r')) return false
        val (introducer, terminator) = when {
            startsWith("<<<<<<< ") -> "<<<<<<< " to ">>>>>>> "
            startsWith(">>>> ") -> ">>>> " to "<<<<\n"
            else -> return false
        }
        var p = pos + introducer.length
        while (p < bufferEnd) {
            if ((buffer[p - 1] == '\n' || buffer[p - 1] == '\r') && startsWith(terminator, p)) {
                pos = p + terminator.length
                while (pos < bufferEnd && buffer[pos] != '\n' && buffer[pos] != '\r') pos++
                return true
            }
            p++
        }
        return false
    }

    // endregion

    // region Number literals

    private fun lexNumber(): IElementType {
        if (at() == '0'.code && at(1) == 'x'.code) return lexHexNumber()
        if (at() == '0'.code && (at(1) == 'o'.code || at(1) == 'b'.code)) {
            val isOctal = at(1) == 'o'.code
            pos += 2
            val digit: (Int) -> Boolean = if (isOctal) { c -> c in '0'.code..'7'.code } else { c -> c == '0'.code || c == '1'.code }
            advanceWhile { digit(it) || it == '_'.code }
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.INTEGER_LITERAL
        }

        advanceWhile { CharacterInfo.isDigit(it) || it == '_'.code }

        if (at() == '.'.code) {
            if (at(1) == EOF || !CharacterInfo.isDigit(at(1)) || prevTokenType == T.DOT) return T.INTEGER_LITERAL
        } else if (at() != 'e'.code && at() != 'E'.code) {
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.INTEGER_LITERAL
        }

        if (advanceMatching('.')) advanceWhile { CharacterInfo.isDigit(it) || it == '_'.code }

        if (isAt('e', 'E')) {
            pos++
            if (isAt('-', '+')) pos++
            if (!CharacterInfo.isDigit(at())) {
                advanceWhile(CharacterInfo::isIdentifierContinuation)
                return T.FLOAT_LITERAL
            }
            advanceWhile { CharacterInfo.isDigit(it) || it == '_'.code }
            advanceWhile(CharacterInfo::isIdentifierContinuation)
        }
        return T.FLOAT_LITERAL
    }

    private fun lexHexNumber(): IElementType {
        pos += 2
        if (!CharacterInfo.isHexDigit(at())) {
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.INTEGER_LITERAL
        }
        advanceWhile { CharacterInfo.isHexDigit(it) || it == '_'.code }

        if (!isAt('.', 'p', 'P')) {
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.INTEGER_LITERAL
        }

        var cursorToDot = -1
        if (at() == '.'.code) {
            cursorToDot = pos
            pos++
            if (!CharacterInfo.isHexDigit(at())) {
                pos = cursorToDot
                return T.INTEGER_LITERAL
            }
            advanceWhile { CharacterInfo.isHexDigit(it) || it == '_'.code }
            if (!isAt('p', 'P')) {
                if (at(1) != EOF && !CharacterInfo.isDigit(at(1))) {
                    pos = cursorToDot
                    return T.INTEGER_LITERAL
                }
                return T.INTEGER_LITERAL
            }
        }

        pos++ // p / P
        var signedExponent = false
        if (isAt('+', '-')) { pos++; signedExponent = true }
        if (!CharacterInfo.isDigit(at())) {
            if (cursorToDot >= 0 && !CharacterInfo.isDigit(charAt(cursorToDot + 1)) && !signedExponent) {
                pos = cursorToDot
                return T.INTEGER_LITERAL
            }
            advanceWhile(CharacterInfo::isIdentifierContinuation)
            return T.FLOAT_LITERAL
        }
        advanceWhile { CharacterInfo.isDigit(it) || it == '_'.code }
        advanceWhile(CharacterInfo::isIdentifierContinuation)
        return T.FLOAT_LITERAL
    }

    // endregion

    // region Pound

    private fun lexPound(): IElementType {
        if (pos == 0 && at(1) == '!'.code) {
            advanceToEndOfLine()
            return T.SHEBANG
        }

        // Raw string literal: `#"`, `##"`, ...
        var p = pos
        while (charAt(p) == '#'.code) p++
        if (charAt(p) == '"'.code) {
            val length = p - pos
            pos = p
            push(State.AfterRawStringDelimiter(length))
            return T.RAW_STRING_DELIMITER
        }

        scanRegexLiteral(pos, mustBeRegex = true)?.let { return emitRegexLexemes(it) }

        val wordStart = pos + 1
        p = wordStart
        while (p < bufferEnd && CharacterInfo.isAsciiIdentifierStart(buffer[p].code)) p++
        val type = when (buffer.subSequence(wordStart, p).toString()) {
            "sourceLocation" -> T.POUND_SOURCE_LOCATION
            "if" -> T.POUND_IF
            "else" -> T.POUND_ELSE
            "elseif" -> T.POUND_ELSEIF
            "endif" -> T.POUND_ENDIF
            "available" -> T.POUND_AVAILABLE
            "unavailable" -> T.POUND_UNAVAILABLE
            else -> null
        }
        if (type == null) {
            pos++
            return T.POUND
        }
        pos = p
        return type
    }

    // endregion

    // region String literals

    private fun transitionAfterStringQuote(kind: StringKind) {
        when (val state = top()) {
            is State.AfterStringLiteral ->
                if (state.isRawString) replace(State.AfterClosingStringQuote) else pop()
            is State.AfterRawStringDelimiter ->
                replace(State.InStringLiteral(kind, state.delimiterLength, atOpening = true))
            else -> push(State.InStringLiteral(kind, 0, atOpening = true))
        }
    }

    private fun lexStringQuote(matchingOpening: StringKind?, leadingDelimiterLength: Int): IElementType {
        if (advanceMatching('\'')) {
            transitionAfterStringQuote(StringKind.SINGLE_QUOTE)
            return T.SINGLE_QUOTE
        }
        pos++ // "

        if (matchingOpening == StringKind.SINGLE_LINE || !(at() == '"'.code && at(1) == '"'.code)) {
            transitionAfterStringQuote(StringKind.SINGLE_LINE)
            return T.STRING_QUOTE
        }

        if (leadingDelimiterLength > 0) {
            // `#"""#` and `#"""abc"#` are single-line raw strings that start with quotes.
            var p = pos + 2
            if (hasStringDelimiter(p, leadingDelimiterLength)) {
                transitionAfterStringQuote(StringKind.SINGLE_LINE)
                return T.STRING_QUOTE
            }
            while (p < bufferEnd && buffer[p] != '\n' && buffer[p] != '\r') {
                if (buffer[p] == '"') {
                    p++
                    if (hasStringDelimiter(p, leadingDelimiterLength)) {
                        transitionAfterStringQuote(StringKind.SINGLE_LINE)
                        return T.STRING_QUOTE
                    }
                    continue
                }
                p++
            }
        }

        pos += 2
        transitionAfterStringQuote(StringKind.MULTI_LINE)
        return T.MULTILINE_STRING_QUOTE
    }

    private fun hasStringDelimiter(from: Int, delimiterLength: Int): Boolean {
        if (delimiterLength == 0) return true
        for (k in 0 until delimiterLength) if (charAt(from + k) != '#'.code) return false
        return true
    }

    private fun isAtInterpolationAnchor(delimiterLength: Int): Boolean =
        at() == '\\'.code && hasStringDelimiter(pos + 1, delimiterLength) && charAt(pos + 1 + delimiterLength) == '('.code

    private fun isAtEscapedNewline(delimiterLength: Int): Boolean {
        if (at() != '\\'.code || !hasStringDelimiter(pos + 1, delimiterLength)) return false
        var p = pos + 1 + delimiterLength
        while (charAt(p) == ' '.code || charAt(p) == '\t'.code) p++
        return charAt(p) == '\n'.code || charAt(p) == '\r'.code
    }

    /**
     * Lexes one token inside a string literal: a segment, an escape sequence, or (by moving to another
     * state) nothing. Returns null when the state changed without consuming text.
     */
    private fun lexInStringLiteral(state: State.InStringLiteral): IElementType? {
        val kind = state.kind
        val delim = state.delimiterLength
        if (state.atOpening) {
            replace(state.copy(atOpening = false))
            if (kind == StringKind.MULTI_LINE && isAt('\n', '\r')) {
                // The newline right after an opening `"""` is not part of the string's value.
                if (advanceMatching('\r')) advanceMatching('\n') else pos++
                return T.WHITE_SPACE
            }
        }

        val segmentStart = pos
        fun segmentOrNull(): IElementType? = if (pos > segmentStart) T.STRING_SEGMENT else null

        while (true) {
            if (pos >= bufferEnd) {
                replace(State.AfterStringLiteral(kind, delim > 0))
                return segmentOrNull()
            }
            when (buffer[pos]) {
                '\\' -> {
                    if (isAtInterpolationAnchor(delim)) {
                        push(State.InStringInterpolationStart(kind))
                        return segmentOrNull()
                    }
                    if (kind == StringKind.MULTI_LINE && isAtEscapedNewline(delim)) {
                        if (pos > segmentStart) return T.STRING_SEGMENT
                        pos += 1 + delim
                        while (isAt(' ', '\t')) pos++
                        if (advanceMatching('\r')) advanceMatching('\n') else pos++
                        return T.ESCAPE_SEQUENCE
                    }
                    if (!hasStringDelimiter(pos + 1, delim)) {
                        pos++ // A plain backslash inside a raw string.
                        continue
                    }
                    if (pos > segmentStart) return T.STRING_SEGMENT
                    pos += 1 + delim
                    return if (lexEscapedCharacter()) T.ESCAPE_SEQUENCE else T.INVALID_ESCAPE_SEQUENCE
                }
                '\n', '\r' -> {
                    if (kind == StringKind.MULTI_LINE) {
                        if (advanceMatching('\r')) advanceMatching('\n') else pos++
                        return T.STRING_SEGMENT
                    }
                    pop()
                    return segmentOrNull()
                }
                '"' -> {
                    when (kind) {
                        StringKind.SINGLE_LINE -> if (hasStringDelimiter(pos + 1, delim)) {
                            replace(State.AfterStringLiteral(kind, delim > 0))
                            return segmentOrNull()
                        }
                        StringKind.MULTI_LINE -> if (charAt(pos + 1) == '"'.code && charAt(pos + 2) == '"'.code &&
                            hasStringDelimiter(pos + 3, delim)
                        ) {
                            replace(State.AfterStringLiteral(kind, delim > 0))
                            return segmentOrNull()
                        }
                        StringKind.SINGLE_QUOTE -> Unit
                    }
                    pos++
                }
                '\'' -> {
                    if (kind == StringKind.SINGLE_QUOTE) {
                        replace(State.AfterStringLiteral(kind, delim > 0))
                        return segmentOrNull()
                    }
                    pos++
                }
                else -> advanceCodePoint()
            }
        }
    }

    /** Lexes the part of an escape sequence after `\` (and the raw delimiter). Returns false if invalid. */
    private fun lexEscapedCharacter(): Boolean {
        when (at()) {
            '0'.code, 'n'.code, 'r'.code, 't'.code, '"'.code, '\''.code, '\\'.code -> { pos++; return true }
            'u'.code -> {
                pos++
                if (at() != '{'.code) return false
                pos++
                val digitsStart = pos
                while (CharacterInfo.isHexDigit(at())) pos++
                val count = pos - digitsStart
                if (!advanceMatching('}')) return false
                if (count !in 1..8) return false
                val value = buffer.subSequence(digitsStart, digitsStart + count).toString().toLong(16)
                return value <= 0x10FFFF && value !in 0xD800..0xDFFF
            }
            EOF, '\n'.code, '\r'.code -> return false
            else -> {
                val cp = codePointAt(pos)
                if (CharacterInfo.isDigit(cp) || CharacterInfo.isLetter(cp)) advanceCodePoint()
                return false
            }
        }
    }

    // endregion

    // region Regex literals

    private fun emitRegexLexemes(lexemes: List<RegexLexeme>): IElementType {
        val first = lexemes[0]
        pos = first.end
        if (lexemes.size > 1) push(State.InRegexLiteral(lexemes, 1))
        return first.type
    }

    private fun isInRegexLiteralPosition(): Boolean = when (prev) {
        PrevClass.DEFAULT, PrevClass.OPEN, PrevClass.FUNC_OR_OPERATOR, PrevClass.TRY -> true
        PrevClass.EXPR_END, PrevClass.PERIOD, PrevClass.PREFER_REGEX -> false
    }

    private fun tryScanOperatorAsRegex(regexStart: Int, operatorStart: Int, operatorEnd: Int, preferRegex: Boolean): List<RegexLexeme>? {
        val leftBound = isLeftBound(operatorStart)
        val rightBound = isRightBound(operatorEnd, leftBound)
        if (leftBound) return null
        if (prev == PrevClass.FUNC_OR_OPERATOR) return null
        var mustBeRegex = false
        if (!rightBound) {
            if (preferRegex) mustBeRegex = true
            if (!mustBeRegex && !isInRegexLiteralPosition()) return null
            if (prev != PrevClass.OPEN) mustBeRegex = true
        }
        return scanRegexLiteral(regexStart, mustBeRegex)
    }

    private enum class RegexResult { DONE, NOT_A_REGEX, UNTERMINATED }

    /** Port of swift-syntax's `RegexLiteralLexer`. Returns the regex lexemes, or null if this is not a regex. */
    private fun scanRegexLiteral(start: Int, mustBeRegex: Boolean): List<RegexLexeme>? {
        var cursor = start
        val lexemes = ArrayList<RegexLexeme>(5)
        var openPounds = 0
        while (charAt(cursor) == '#'.code) { cursor++; openPounds++ }
        if (openPounds > 0) lexemes += RegexLexeme(T.REGEX_POUND_DELIMITER, cursor)
        val openSlash = cursor
        if (charAt(cursor) != '/'.code) return null
        cursor++
        lexemes += RegexLexeme(T.REGEX_SLASH, cursor)
        val patternStart = cursor

        if (openPounds == 0) {
            when (charAt(cursor)) {
                ' '.code, '\t'.code -> if (!mustBeRegex) return null
                '*'.code, '/'.code -> return null
            }
            if (charAt(openSlash - 1) == '*'.code) return null
        }

        var firstNewline = -1
        if (openPounds > 0) {
            var scan = cursor
            while (charAt(scan) == ' '.code || charAt(scan) == '\t'.code) scan++
            if (charAt(scan) == '\n'.code || charAt(scan) == '\r'.code) {
                firstNewline = scan
                cursor = scan
            }
        }

        var groupDepth = 0
        var classDepth = 0
        var lastUnescapedSpace = -1
        var closeSlashStart = -1
        var closeEnd = -1

        val result: RegexResult
        outer@ while (true) {
            // Try to eat the ending delimiter.
            if (charAt(cursor) == '/'.code) {
                var c = cursor + 1
                var closePounds = 0
                if (openPounds > 0) while (charAt(c) == '#'.code) { c++; closePounds++ }
                if (closePounds >= openPounds) {
                    if (closePounds == 0) {
                        if (charAt(c) == '*'.code || charAt(c) == '/'.code) { result = RegexResult.UNTERMINATED; break@outer }
                        if (lastUnescapedSpace >= 0 && lastUnescapedSpace + 1 == cursor && !mustBeRegex) {
                            result = RegexResult.NOT_A_REGEX; break@outer
                        }
                    }
                    closeSlashStart = cursor
                    closeEnd = c
                    cursor = c
                    result = RegexResult.DONE
                    break@outer
                }
            }

            // Lex a pattern character.
            var escaped = false
            while (true) {
                if (cursor >= bufferEnd) {
                    if (firstNewline >= 0) cursor = firstNewline
                    result = RegexResult.UNTERMINATED
                    break@outer
                }
                val ch = buffer[cursor]
                val chPos = cursor
                cursor++
                when {
                    ch == '\n' || ch == '\r' -> {
                        if (firstNewline < 0) {
                            cursor = chPos
                            result = RegexResult.UNTERMINATED
                            break@outer
                        }
                    }
                    ch == '\\' && !escaped -> { escaped = true; continue }
                    ch == '(' && !escaped && classDepth == 0 -> groupDepth++
                    ch == ')' && !escaped && classDepth == 0 -> {
                        if (groupDepth == 0) {
                            if (!mustBeRegex) { result = RegexResult.NOT_A_REGEX; break@outer }
                        } else {
                            groupDepth--
                        }
                    }
                    ch == '[' && !escaped -> classDepth++
                    ch == ']' && !escaped -> if (classDepth > 0) classDepth--
                    (ch == ' ' || ch == '\t') && !escaped -> lastUnescapedSpace = chPos
                    Character.isHighSurrogate(ch) && cursor < bufferEnd && Character.isLowSurrogate(buffer[cursor]) -> cursor++
                }
                break
            }
        }

        when (result) {
            RegexResult.NOT_A_REGEX -> return null
            RegexResult.UNTERMINATED -> if (!mustBeRegex) return null
            RegexResult.DONE -> Unit
        }

        if (result == RegexResult.DONE) {
            if (closeSlashStart > patternStart) lexemes += RegexLexeme(T.REGEX_PATTERN, closeSlashStart)
            lexemes += RegexLexeme(T.REGEX_SLASH, closeSlashStart + 1)
            if (closeEnd > closeSlashStart + 1) lexemes += RegexLexeme(T.REGEX_POUND_DELIMITER, closeEnd)
        } else if (cursor > patternStart) {
            lexemes += RegexLexeme(T.REGEX_PATTERN, cursor)
        }
        return lexemes
    }

    // endregion

    /**
     * What swift-syntax's lexer remembers about the previous token (`previousTokenKind`,
     * `previousKeyword`), reduced to the distinctions that change how the next token is lexed.
     * The ordinal is the lexer state when the state stack is empty.
     */
    internal enum class PrevClass {
        /** Start of input, or a token after which a `/` may start a regex literal and binary use is unlikely. */
        DEFAULT,
        /** `(`, `[`, `,`, `:` – a regex may start here, but a spaced `/` may also be an operator reference. */
        OPEN,
        /** The end of an expression (identifier, literal, closing bracket, ...): a `/` is division. */
        EXPR_END,
        /** `.` – also makes `1.2` in `x.1.2` lex as two integers. */
        PERIOD,
        /** `func` or `operator`: an operator here is a name, never a regex. */
        FUNC_OR_OPERATOR,
        /** `try`: a following `?` or `!` makes the lexer prefer a regex over a binary operator. */
        TRY,
        /** The state after `try?` / `try!`. Only used as an encoded lexer state. */
        PREFER_REGEX;

        companion object {
            fun of(type: IElementType): PrevClass {
                if (type is SwiftKeywordTokenType) {
                    return when (type.keyword) {
                        "true", "false", "Any", "nil", "self", "Self", "super" -> EXPR_END
                        "func", "operator" -> FUNC_OR_OPERATOR
                        "try" -> TRY
                        else -> DEFAULT
                    }
                }
                return when (type) {
                    T.LPAR, T.LBRACKET, T.COMMA, T.COLON -> OPEN
                    T.LBRACE, T.PREFIX_OPERATOR, T.PREFIX_AMP, T.BINARY_OPERATOR, T.EQ, T.SEMICOLON,
                    T.INFIX_QUESTION, T.POUND_IF, T.POUND_ELSE, T.POUND_ELSEIF, T.POUND_ENDIF, T.BAD_CHARACTER -> DEFAULT
                    T.DOT -> PERIOD
                    else -> EXPR_END
                }
            }
        }
    }

    companion object {
        private const val EOF = -1

        /** State reported for tokens inside string literals, interpolations and regex literals. */
        private const val NESTED_STATE = 100
    }
}
