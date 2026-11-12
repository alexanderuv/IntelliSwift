package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywords
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/**
 * The significant (non-whitespace, non-comment) tokens of the text being parsed, with the
 * whitespace facts swift-syntax's parser consults: whether a token starts a line, and whether
 * trivia separates it from its neighbours.
 *
 * The parser drives a [PsiBuilder], but all lookahead runs on this array instead of builder
 * markers. That keeps lookahead free of side effects such as token remapping, and lets it be
 * written the way swift-syntax's `Lookahead` is: a cursor that can be copied and advanced freely.
 */
internal class TokenStream(private val text: CharSequence) {
    private var types = arrayOfNulls<IElementType>(256)
    private var starts = IntArray(256)
    private var ends = IntArray(256)
    private var startOfLine = BooleanArray(256)
    private var triviaBefore = BooleanArray(256)
    private var continuesOperator = BooleanArray(256)
    var size = 0
        private set

    init {
        val lexer = SwiftLexer()
        lexer.start(text, 0, text.length, 0)
        var newline = false
        var trivia = false
        while (true) {
            val type = lexer.tokenType ?: break
            if (type == T.WHITE_SPACE || type in T.COMMENTS) {
                trivia = true
                if (!newline) {
                    for (i in lexer.tokenStart until lexer.tokenEnd) {
                        val c = text[i]
                        if (c == '\n' || c == '\r') { newline = true; break }
                    }
                }
            } else {
                add(type, lexer.tokenStart, lexer.tokenEnd, newline, trivia, lexer.continuesOperator)
                newline = false
                trivia = false
            }
            lexer.advance()
        }
    }

    private fun add(type: IElementType, start: Int, end: Int, sol: Boolean, trivia: Boolean, continues: Boolean) {
        if (size == types.size) {
            val n = size * 2
            types = types.copyOf(n)
            starts = starts.copyOf(n)
            ends = ends.copyOf(n)
            startOfLine = startOfLine.copyOf(n)
            triviaBefore = triviaBefore.copyOf(n)
            continuesOperator = continuesOperator.copyOf(n)
        }
        types[size] = type
        starts[size] = start
        ends[size] = end
        startOfLine[size] = sol || size == 0
        triviaBefore[size] = trivia
        continuesOperator[size] = continues
        size++
    }

    fun type(i: Int): IElementType? = if (i in 0 until size) types[i] else null
    fun start(i: Int): Int = if (i < size) starts[i] else text.length
    fun end(i: Int): Int = if (i < size) ends[i] else text.length
    fun text(i: Int): CharSequence = if (i < size) text.subSequence(starts[i], ends[i]) else ""
    fun textIs(i: Int, s: String): Boolean {
        if (i >= size) return false
        val len = ends[i] - starts[i]
        if (len != s.length) return false
        for (k in 0 until len) if (text[starts[i] + k] != s[k]) return false
        return true
    }
    fun firstChar(i: Int): Char = if (i < size && ends[i] > starts[i]) text[starts[i]] else '\u0000'
    fun isAtStartOfLine(i: Int): Boolean = if (i < size) startOfLine[i] else true
    fun hasTriviaBefore(i: Int): Boolean = if (i < size) triviaBefore[i] else true
    fun hasTriviaAfter(i: Int): Boolean = hasTriviaBefore(i + 1)
    /** Whether token [i] is a piece of the operator before it; see [SwiftLexer.continuesOperator]. */
    fun continuesOperator(i: Int): Boolean = i < size && continuesOperator[i]
    fun charBefore(i: Int): Char = if (i < size && starts[i] > 0) text[starts[i] - 1] else '\u0000'
    fun charAfter(i: Int): Char = if (i < size && ends[i] < text.length) text[ends[i]] else '\u0000'
}

/**
 * Token predicates shared by the parser and lookahead, mirroring swift-syntax's `TokenConsumer`.
 * Contextual keywords are matched by text on [T.IDENTIFIER] tokens, as swift-syntax does.
 */
internal abstract class TokenCursor(val tokens: TokenStream) {
    abstract val pos: Int

    fun type(k: Int = 0): IElementType? = tokens.type(pos + k)
    fun text(k: Int = 0): CharSequence = tokens.text(pos + k)
    fun eof(): Boolean = pos >= tokens.size

    fun at(t: IElementType, k: Int = 0): Boolean = tokens.type(pos + k) == t
    fun atAny(vararg ts: IElementType): Boolean { val c = type(); return ts.any { it == c } }

    /** At the contextual keyword [kw] (an identifier with this text) or the reserved keyword [kw]. */
    fun atKw(kw: String, k: Int = 0): Boolean {
        val t = tokens.type(pos + k) ?: return false
        if (t is SwiftKeywordTokenType) return t.keyword == kw
        return (t == T.IDENTIFIER) && tokens.textIs(pos + k, kw)
    }
    fun atAnyKw(vararg kws: String, k: Int = 0): Boolean = kws.any { atKw(it, k) }

    fun atText(s: String, k: Int = 0): Boolean = tokens.textIs(pos + k, s)

    /** An operator-like token whose text is exactly [s] (swift-syntax's `atContextualPunctuator`). */
    fun atPunct(s: String, k: Int = 0): Boolean {
        val t = tokens.type(pos + k) ?: return false
        return isOperatorLike(t) && tokens.textIs(pos + k, s)
    }

    fun atStartOfLine(k: Int = 0): Boolean = tokens.isAtStartOfLine(pos + k)
    fun hasTriviaBefore(k: Int = 0): Boolean = tokens.hasTriviaBefore(pos + k)

    fun atIdentifier(k: Int = 0): Boolean = at(T.IDENTIFIER, k)

    /** True for tokens that may name something after `.` or as an argument label: identifiers and keywords. */
    fun atIdentifierOrKeyword(k: Int = 0): Boolean {
        val t = type(k) ?: return false
        return t == T.IDENTIFIER || t is SwiftKeywordTokenType
    }

    fun atReservedKeyword(k: Int = 0): Boolean = (type(k) as? SwiftKeywordTokenType)?.isReserved == true

    /** swift-syntax's `atArgumentLabel`: any identifier or keyword except a few reserved ones; `_`. */
    fun atArgumentLabel(allowDollar: Boolean = false, k: Int = 0): Boolean {
        val t = type(k) ?: return false
        if (t == T.WILDCARD || t == T.IDENTIFIER) return true
        if (allowDollar && t == T.DOLLAR_IDENTIFIER) return true
        if (t is SwiftKeywordTokenType) return t.keyword !in NON_LABEL_KEYWORDS
        return false
    }

    companion object {
        val NON_LABEL_KEYWORDS = setOf("inout", "var", "let")
        val SPECIAL_NAMES = setOf("self", "Self", "init")

        fun isOperatorLike(t: IElementType): Boolean = t == T.BINARY_OPERATOR || t == T.PREFIX_OPERATOR ||
            t == T.POSTFIX_OPERATOR || t == T.EQ || t == T.PREFIX_AMP || t == T.POSTFIX_QUESTION ||
            t == T.INFIX_QUESTION || t == T.EXCLAMATION || t == T.ARROW || t == T.DOT
    }
}

/** A side-effect-free cursor for lookahead, like swift-syntax's `Parser.Lookahead`. */
internal class Lookahead(tokens: TokenStream, override var pos: Int) : TokenCursor(tokens) {
    fun bump() { if (pos < tokens.size) pos++ }
    fun bump(n: Int) { pos = minOf(pos + n, tokens.size) }
    fun copy(): Lookahead = Lookahead(tokens, pos)

    fun consume(t: IElementType): Boolean { if (at(t)) { bump(); return true }; return false }
    fun consumeKw(kw: String): Boolean { if (atKw(kw)) { bump(); return true }; return false }
    fun consumePunct(s: String): Boolean { if (atPunct(s)) { bump(); return true }; return false }

    /** Skips one token, or a balanced `()`, `[]`, `{}` or `<...>`-free group starting here. */
    fun skipSingle() {
        val close = when (type()) {
            T.LPAR -> T.RPAR
            T.LBRACKET -> T.RBRACKET
            T.LBRACE -> T.RBRACE
            T.POUND_IF -> T.POUND_ENDIF
            else -> null
        }
        bump()
        if (close != null) {
            skipUntil(close)
            consume(close)
        }
    }

    /** Skips tokens until [t1] or [t2] or a closing bracket at this nesting level. */
    fun skipUntil(t1: IElementType, t2: IElementType = t1) {
        while (!eof()) {
            val t = type()
            if (t == t1 || t == t2) return
            if (t == T.RPAR || t == T.RBRACKET || t == T.RBRACE || t == T.POUND_ENDIF) return
            skipSingle()
        }
    }
}

/**
 * The parser state: a [PsiBuilder] plus the [TokenStream] cursor kept in step with it.
 * Grammar rules are extension functions in the other files of this package.
 */
internal class SwiftParserCore(val b: PsiBuilder) : TokenCursor(TokenStream(b.originalText)) {
    override var pos: Int = 0
        private set

    /** Nesting depth of expressions, to stop runaway recursion on pathological input. */
    var depth = 0

    fun la(k: Int = 0): Lookahead = Lookahead(tokens, pos + k)

    inline fun <R> lookahead(block: Lookahead.() -> R): R = la().block()

    fun mark(): PsiBuilder.Marker = b.mark()

    fun advance() {
        if (pos < tokens.size) {
            b.advanceLexer()
            pos++
        }
    }

    fun bump(): Boolean { advance(); return true }

    /**
     * Remaps the current token. PsiBuilder skips whitespace lazily, in `getTokenType()`, so that
     * has to run first or the remap would hit a preceding whitespace token.
     */
    private fun remap(type: IElementType) {
        b.tokenType
        b.remapCurrentToken(type)
    }

    fun bumpAs(type: IElementType) {
        if (pos < tokens.size) remap(type)
        advance()
    }

    /** Consumes the contextual keyword at the cursor, remapping it to its keyword token type. */
    fun bumpKw() {
        val t = type()
        if (t == T.IDENTIFIER) SwiftKeywords.keyword(text())?.let { remap(it) }
        advance()
    }

    /**
     * Consumes a name. swift-syntax turns reserved keywords used as names (argument labels,
     * parameter names, members after `.`) into identifiers. With [keepSpecialNames], `self`,
     * `Self` and `init` keep their meaning, as they do after `.`.
     */
    fun bumpName(keepSpecialNames: Boolean = false) {
        val t = type()
        if (t is SwiftKeywordTokenType && !(keepSpecialNames && t.keyword in SPECIAL_NAMES)) remap(T.IDENTIFIER)
        advance()
    }

    /** Consumes an operator used as a declared name; swift-syntax spells these as binary operators. */
    fun bumpOperatorName() {
        val n = operatorRunLength()
        if (n <= 1) {
            if (type() == T.PREFIX_OPERATOR || type() == T.POSTFIX_OPERATOR || type() == T.PREFIX_AMP ||
                type() == T.EXCLAMATION || type() == T.POSTFIX_QUESTION || type() == T.INFIX_QUESTION || type() == T.EQ
            ) remap(T.BINARY_OPERATOR)
            advance()
            return
        }
        val m = mark()
        repeat(n) { advance() }
        m.collapse(T.BINARY_OPERATOR)
    }

    fun consume(t: IElementType): Boolean { if (at(t)) { advance(); return true }; return false }
    fun consumeKw(kw: String): Boolean { if (atKw(kw)) { bumpKw(); return true }; return false }

    fun consumePunct(s: String, remap: IElementType? = null): Boolean {
        if (!atPunct(s)) return false
        if (remap != null) bumpAs(remap) else advance()
        return true
    }

    /**
     * Consumes [t], like swift-syntax's `expect`: if it is not at the cursor but follows after
     * tokens of lower [TokenPrecedence], those are wrapped in an error element and [t] is consumed;
     * otherwise [t] is reported missing.
     */
    fun expect(t: IElementType, what: String): Boolean {
        if (consume(t)) return true
        val skip = lookahead { canRecoverTo(TokenPrecedence.of(t)) { it.at(t) } }
        if (skip == null) {
            error("Expected $what")
            return false
        }
        val m = mark()
        repeat(skip) { advance() }
        m.error("Unexpected code before $what")
        advance()
        return true
    }

    /** Drops a marker [m] started at token [start] if nothing was consumed since. */
    fun discardIfEmpty(m: PsiBuilder.Marker, start: Int): Boolean {
        if (pos != start) return false
        m.rollbackTo()
        return true
    }

    fun expectKw(kw: String): Boolean {
        if (consumeKw(kw)) return true
        error("Expected '$kw'")
        return false
    }

    fun error(message: String) { b.error(message) }

    /** Wraps the current token in an error element. */
    fun errorToken(message: String) {
        val m = mark()
        advance()
        m.error(message)
    }

    fun expectIdentifier(what: String = "identifier"): Boolean {
        if (atIdentifier()) { advance(); return true }
        error("Expected $what")
        return false
    }

    /**
     * Consumes an operator the lexer split at a leading `<` or `>` (`>>`, `>=`, `<=`, `<-`, ...),
     * collapsing the pieces into one leaf of the operator kind swift-syntax would have lexed.
     * Returns the number of lexer tokens consumed.
     */
    fun bumpOperator(): Int {
        val n = operatorRunLength()
        if (n <= 1) {
            advance()
            return 1
        }
        val m = mark()
        repeat(n) { advance() }
        m.collapse(classifyMergedOperator(pos - n, pos - 1))
        return n
    }

    /** How many adjacent tokens form the operator at the cursor (see [bumpOperator]). */
    fun operatorRunLength(k: Int = 0): Int {
        val first = pos + k
        var i = first
        while (tokens.continuesOperator(i + 1)) i++
        return i - first + 1
    }

    /** The operator kind of the merged run [first]..[last] by swift-syntax's boundness rules. */
    fun classifyMergedOperator(first: Int, last: Int): IElementType {
        val leftBound = when (tokens.charBefore(first)) {
            ' ', '\r', '\n', '\t', '(', '[', '{', ',', ';', ':', '\u0000' -> false
            else -> !tokens.hasTriviaBefore(first)
        }
        val rightBound = if (tokens.hasTriviaAfter(last)) false else when (tokens.charAfter(last)) {
            ' ', '\r', '\n', '\t', ')', ']', '}', ',', ';', ':', '\u0000' -> false
            '.' -> !leftBound
            else -> true
        }
        return when {
            leftBound == rightBound -> T.BINARY_OPERATOR
            leftBound -> T.POSTFIX_OPERATOR
            else -> T.PREFIX_OPERATOR
        }
    }

    /** The kind the operator at the cursor has once merged with [operatorRunLength]. */
    fun effectiveOperatorType(k: Int = 0): IElementType? {
        val n = operatorRunLength(k)
        if (n <= 1) return type(k)
        return classifyMergedOperator(pos + k, pos + k + n - 1)
    }

    /** Text of the operator at the cursor including merged pieces. */
    fun effectiveOperatorText(k: Int = 0): String {
        val n = operatorRunLength(k)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(tokens.text(pos + k + i))
        return sb.toString()
    }

    /** Runs [block] and keeps going only if it consumed something; guards loops against no progress. */
    inline fun progressing(block: () -> Unit): Boolean {
        val before = pos
        block()
        return pos > before
    }
}
