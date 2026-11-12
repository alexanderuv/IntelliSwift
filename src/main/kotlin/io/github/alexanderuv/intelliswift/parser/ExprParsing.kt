package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.KW
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/*
 * Expressions, following swift-syntax's `Expressions.swift`. Like swift-syntax, binary operator
 * chains are left unfolded as a flat SEQUENCE_EXPR, since folding needs operator declarations
 * that may live in other files.
 */

/** A completed expression node and its type. */
internal class Parsed(val marker: PsiBuilder.Marker, val type: IElementType)

private const val MAX_DEPTH = 256

private val LITERALS_WITHOUT_TRAILING_CLOSURE = setOf(
    E.BOOLEAN_LITERAL_EXPR, E.FLOAT_LITERAL_EXPR, E.INTEGER_LITERAL_EXPR, E.NIL_LITERAL_EXPR,
    E.STRING_LITERAL_EXPR, E.REGEX_LITERAL_EXPR, E.ARRAY_EXPR, E.DICTIONARY_EXPR,
)

internal fun SwiftParserCore.done(m: PsiBuilder.Marker, type: IElementType): Parsed {
    m.done(type)
    return Parsed(m, type)
}

/** Parses an expression; reports "Expected expression" and returns null if there is none. */
internal fun SwiftParserCore.parseExpression(
    flavor: ExprFlavor = ExprFlavor.BASIC,
    pattern: PatternContext = PatternContext.NONE,
): IElementType? {
    if (pattern != PatternContext.NONE) {
        if (at(KW.IS) || (atAnyKw(*BINDING_INTRODUCERS) && la().shouldParsePatternBinding())) {
            val m = mark()
            val inner = parseMatchingPattern(PatternContext.MATCHING)
            if (inner == null) { m.drop(); return null }
            return done(m, E.PATTERN_EXPR).type
        }
    }
    return parseSequenceExpression(flavor, pattern)
}

private val BINDING_INTRODUCERS = PATTERN_BINDING_INTRODUCERS.toTypedArray()

internal fun SwiftParserCore.parseSequenceExpression(flavor: ExprFlavor, pattern: PatternContext = PatternContext.NONE): IElementType? {
    if (flavor == ExprFlavor.POUND_IF_DIRECTIVE && atStartOfLine()) {
        error("Expected condition")
        return null
    }
    val m = mark()
    var last = parseSequenceExpressionElement(flavor, pattern)
    if (last == null) {
        m.drop()
        return null
    }
    var operators = 0
    while (true) {
        if (flavor == ExprFlavor.POUND_IF_DIRECTIVE && atStartOfLine()) break
        val op = parseSequenceExpressionOperator(flavor, pattern) ?: break
        operators++
        if (op) continue // The operator parsed its right-hand side (`is T`, `as T`).
        if (flavor == ExprFlavor.POUND_IF_DIRECTIVE && atStartOfLine()) break
        last = parseSequenceExpressionElement(flavor, pattern)
        if (last == null) break
    }
    if (operators == 0) {
        m.drop()
        return last
    }
    m.done(E.SEQUENCE_EXPR)
    return E.SEQUENCE_EXPR
}

/**
 * Parses the operator between two sequence elements. Returns null if there is none, true if the
 * operator also consumed its right-hand side, false otherwise.
 */
private fun SwiftParserCore.parseSequenceExpressionOperator(flavor: ExprFlavor, pattern: PatternContext): Boolean? {
    val t = type() ?: return null
    if (t == KW.IS) {
        val m = mark()
        advance()
        m.done(E.UNRESOLVED_IS_EXPR)
        parseTypeExpr()
        return true
    }
    if (t == KW.AS) {
        val m = mark()
        advance()
        if (at(T.POSTFIX_QUESTION) || at(T.EXCLAMATION)) advance()
        m.done(E.UNRESOLVED_AS_EXPR)
        parseTypeExpr()
        return true
    }
    when (t) {
        T.INFIX_QUESTION -> {
            val m = mark()
            advance()
            if (parseSequenceExpression(flavor, pattern) == null) error("Expected expression")
            expect(T.COLON, "':' after '? ...' in ternary expression")
            m.done(E.UNRESOLVED_TERNARY_EXPR)
            return false
        }
        T.EQ -> {
            if (pattern != PatternContext.NONE) return null
            val m = mark()
            advance()
            m.done(E.ASSIGNMENT_EXPR)
            return false
        }
        T.ARROW -> {
            val m = mark()
            advance()
            m.done(E.ARROW_EXPR)
            return false
        }
    }
    if (atKw("async") && (at(T.ARROW, 1) || atKw("throws", 1)) || at(KW.THROWS)) {
        val m = mark()
        parseEffectSpecifiers(E.TYPE_EFFECT_SPECIFIERS)
        expect(T.ARROW, "'->'")
        m.done(E.ARROW_EXPR)
        return false
    }
    if (effectiveOperatorType() == T.BINARY_OPERATOR) {
        val m = mark()
        bumpOperator()
        m.done(E.BINARY_OPERATOR_EXPR)
        return false
    }
    return null
}

private fun SwiftParserCore.parseTypeExpr() {
    val m = mark()
    parseType()
    m.done(E.TYPE_EXPR)
}

private fun SwiftParserCore.canParseNonisolatedAsSpecifierInExpressionContext(): Boolean {
    if (!atKw("nonisolated")) return false
    return lookahead {
        bump()
        if (atStartOfLine()) return@lookahead false
        if (!consume(T.LPAR)) return@lookahead false
        if (!atKw("nonsending") || atStartOfLine()) return@lookahead false
        bump()
        at(T.RPAR) && !atStartOfLine()
    }
}

internal fun SwiftParserCore.parseSequenceExpressionElement(flavor: ExprFlavor, pattern: PatternContext = PatternContext.NONE): IElementType? {
    if (depth > MAX_DEPTH) {
        error("Expression nested too deeply")
        return null
    }
    depth++
    try {
        if (at(T.AT) || at(KW.INOUT) || canParseNonisolatedAsSpecifierInExpressionContext()) {
            if (la().canParseType()) {
                val m = mark()
                parseType()
                return done(m, E.TYPE_EXPR).type
            }
        }
        parseSequenceElementPrefix(flavor, pattern)?.let { return it }
        return parseUnaryExpression(flavor, pattern)
    } finally {
        depth--
    }
}

private fun SwiftParserCore.prefixed(type: IElementType, flavor: ExprFlavor, pattern: PatternContext, extra: () -> Unit = {}): IElementType {
    val m = mark()
    bumpKw()
    extra()
    if (parseSequenceExpressionElement(flavor, pattern) == null) error("Expected expression")
    return done(m, type).type
}

private fun SwiftParserCore.parseSequenceElementPrefix(flavor: ExprFlavor, pattern: PatternContext): IElementType? {
    val t = type() ?: return null
    if (t == KW.TRY) {
        return prefixed(E.TRY_EXPR, flavor, pattern) {
            if (at(T.EXCLAMATION) || at(T.POSTFIX_QUESTION)) advance()
        }
    }
    if (t == KW.REPEAT) return prefixed(E.PACK_EXPANSION_EXPR, flavor, pattern)
    if (t != T.IDENTIFIER) return null
    return when {
        atKw("await") -> prefixed(E.AWAIT_EXPR, flavor, pattern)
        atKw("unsafe") && la().atContextualKeywordPrefixedSyntax(flavor, acceptClosure = true, preferPostfixExpr = false) ->
            prefixed(E.UNSAFE_EXPR, flavor, pattern)
        (atKw("borrow") || atKw("_borrow")) && la().atContextualKeywordPrefixedSyntax(flavor) ->
            prefixed(E.BORROW_EXPR, flavor, pattern)
        atKw("copy") && la().atContextualKeywordPrefixedSyntax(flavor) -> prefixed(E.COPY_EXPR, flavor, pattern)
        (atKw("consume") || atKw("_move")) && la().atContextualKeywordPrefixedSyntax(flavor) ->
            prefixed(E.CONSUME_EXPR, flavor, pattern)
        atKw("each") && la().atContextualKeywordPrefixedSyntax(flavor) -> prefixed(E.PACK_ELEMENT_EXPR, flavor, pattern)
        atKw("any") && (la().atContextualKeywordPrefixedSyntax(flavor) || atText("~", 1)) -> {
            val m = mark()
            parseType()
            done(m, E.TYPE_EXPR).type
        }
        else -> null
    }
}

internal fun SwiftParserCore.parseUnaryExpression(flavor: ExprFlavor, pattern: PatternContext = PatternContext.NONE): IElementType? {
    when (type()) {
        KW.IF -> return parseIfExpression()
        KW.SWITCH -> return parseSwitchExpression()
        T.PREFIX_AMP -> {
            val m = mark()
            advance()
            if (parseUnaryExpression(flavor, pattern) == null) error("Expected expression")
            return done(m, E.INOUT_EXPR).type
        }
        T.BACKSLASH -> return parseKeyPathExpression(pattern)
    }
    if (effectiveOperatorType() == T.PREFIX_OPERATOR) {
        val m = mark()
        bumpOperator()
        if (parseUnaryExpression(flavor, pattern) == null) error("Expected expression")
        return done(m, E.PREFIX_OPERATOR_EXPR).type
    }
    return parsePostfixExpression(flavor, pattern)
}

private fun SwiftParserCore.parsePostfixExpression(flavor: ExprFlavor, pattern: PatternContext): IElementType? {
    val head = parsePrimaryExpression(flavor, pattern) ?: return null
    return parsePostfixExpressionSuffix(head, flavor, pattern).type
}

internal fun SwiftParserCore.parsePostfixExpressionSuffix(start: Parsed, flavor: ExprFlavor, pattern: PatternContext): Parsed {
    var lhs = start
    while (true) {
        if (flavor == ExprFlavor.POUND_IF_DIRECTIVE && atStartOfLine()) return lhs
        when {
            at(T.DOT) -> {
                lhs = parseDottedExpressionSuffix(lhs)
                continue
            }
            at(T.LPAR) && !atStartOfLine() -> {
                val m = lhs.marker.precede()
                parseArgumentList(T.LPAR, T.RPAR, pattern, flavor.callArgumentFlavor())
                if (flavor == ExprFlavor.BASIC && at(T.LBRACE) && la().atValidTrailingClosure(flavor)) parseTrailingClosures(flavor)
                lhs = done(m, E.FUNCTION_CALL_EXPR)
                continue
            }
            at(T.LBRACKET) && !atStartOfLine() -> {
                val m = lhs.marker.precede()
                parseArgumentList(T.LBRACKET, T.RBRACKET, pattern, ExprFlavor.BASIC)
                if (flavor == ExprFlavor.BASIC && at(T.LBRACE) && la().atValidTrailingClosure(flavor)) parseTrailingClosures(flavor)
                lhs = done(m, E.SUBSCRIPT_CALL_EXPR)
                continue
            }
            at(T.LBRACE) && lhs.type !in LITERALS_WITHOUT_TRAILING_CLOSURE && la().atValidTrailingClosure(flavor) -> {
                val m = lhs.marker.precede()
                parseTrailingClosures(flavor)
                lhs = done(m, E.FUNCTION_CALL_EXPR)
                if (at(T.LBRACE)) return lhs
                continue
            }
            at(T.POSTFIX_QUESTION) -> {
                val m = lhs.marker.precede()
                advance()
                lhs = done(m, E.OPTIONAL_CHAINING_EXPR)
                continue
            }
            at(T.EXCLAMATION) -> {
                val m = lhs.marker.precede()
                advance()
                lhs = done(m, E.FORCE_UNWRAP_EXPR)
                continue
            }
            effectiveOperatorType() == T.POSTFIX_OPERATOR -> {
                val m = lhs.marker.precede()
                bumpOperator()
                lhs = done(m, E.POSTFIX_OPERATOR_EXPR)
                continue
            }
            at(T.POUND_IF) && atStartOfPostfixIfConfig() -> {
                val m = lhs.marker.precede()
                parseIfConfig(IfConfigContext.POSTFIX_EXPRESSION)
                lhs = done(m, E.POSTFIX_IF_CONFIG_EXPR)
                continue
            }
        }
        return lhs
    }
}

private fun ExprFlavor.callArgumentFlavor(): ExprFlavor = when (this) {
    ExprFlavor.BASIC, ExprFlavor.POUND_IF_DIRECTIVE, ExprFlavor.STMT_CONDITION -> ExprFlavor.BASIC
    ExprFlavor.ATTRIBUTE_ARGUMENTS -> ExprFlavor.ATTRIBUTE_ARGUMENTS
}

/** `#if` after an expression whose clauses start with `.member`, as in SwiftUI modifier chains. */
private fun SwiftParserCore.atStartOfPostfixIfConfig(): Boolean = lookahead {
    while (at(T.POUND_IF)) {
        bump()
        while (!eof() && !atStartOfLine()) skipSingle()
    }
    at(T.DOT) && (at(T.INTEGER_LITERAL, 1) || atReservedKeyword(1) || at(T.IDENTIFIER, 1))
}

private fun SwiftParserCore.parseDottedExpressionSuffix(lhs: Parsed): Parsed {
    val m = lhs.marker.precede()
    advance() // .
    if (at(T.INTEGER_LITERAL) || at(KW.SELF)) {
        val n = mark()
        advance()
        n.done(E.DECL_REFERENCE_EXPR)
    } else {
        parseDeclReferenceExpr(allowKeywords = true)
    }
    var result = done(m, E.MEMBER_ACCESS_EXPR)
    if (la().canParseAsGenericArgumentList()) {
        val g = result.marker.precede()
        parseGenericArguments()
        result = done(g, E.GENERIC_SPECIALIZATION_EXPR)
    }
    return result
}

/**
 * Parses a declaration name: identifier, keyword (after `.`), operator or `$0`, optionally
 * followed by a compound name's argument labels `(a:b:)`.
 */
internal fun SwiftParserCore.parseDeclReferenceExpr(
    allowKeywords: Boolean = false,
    allowOperators: Boolean = false,
    compoundNames: Boolean = true,
): Parsed? {
    val m = mark()
    if (atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) {
        advance()
        advance()
    } else if (at(T.COLON_COLON)) {
        advance()
    }
    val t = type()
    when {
        t == T.IDENTIFIER || t == T.DOLLAR_IDENTIFIER || t == T.WILDCARD -> advance()
        t == KW.SELF || t == KW.SELF_TYPE || t == KW.INIT -> advance()
        allowKeywords && t is SwiftKeywordTokenType -> bumpName(keepSpecialNames = true)
        t == KW.DEINIT || t == KW.SUBSCRIPT -> advance()
        allowOperators && t != null && TokenCursor.isOperatorLike(t) -> bumpOperator()
        else -> {
            m.drop()
            error("Expected identifier")
            return null
        }
    }
    if (compoundNames && at(T.LPAR) && !atStartOfLine() && !hasTriviaBefore() && la().canParseArgumentLabelList()) {
        val args = mark()
        advance()
        while (!at(T.RPAR) && !eof()) {
            advance() // label; swift-syntax keeps keywords as keywords here, e.g. `f(for:)`
            advance() // :
        }
        expect(T.RPAR, "')'")
        args.done(E.DECLARATION_NAME_ARGUMENTS)
    }
    return done(m, E.DECL_REFERENCE_EXPR)
}

private fun Lookahead.canParseArgumentLabelList(): Boolean {
    if (!consume(T.LPAR)) return false
    var any = false
    while (!at(T.RPAR)) {
        if (!(atArgumentLabel() || at(T.WILDCARD))) return false
        bump()
        if (!consume(T.COLON)) return false
        any = true
    }
    return any
}

internal fun SwiftParserCore.parsePrimaryExpression(flavor: ExprFlavor, pattern: PatternContext): Parsed? {
    val t = type()
    when (t) {
        T.INTEGER_LITERAL -> return leaf(E.INTEGER_LITERAL_EXPR)
        T.FLOAT_LITERAL -> return leaf(E.FLOAT_LITERAL_EXPR)
        T.AT, T.RAW_STRING_DELIMITER, T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE, T.SINGLE_QUOTE -> return parseStringLiteral()
        T.REGEX_POUND_DELIMITER, T.REGEX_SLASH -> return parseRegexLiteral()
        KW.NIL -> return leaf(E.NIL_LITERAL_EXPR)
        KW.TRUE, KW.FALSE -> return leaf(E.BOOLEAN_LITERAL_EXPR)
        T.COLON_COLON -> return parseIdentifierExpression(flavor)
        T.IDENTIFIER, KW.SELF, KW.INIT, KW.DEINIT, KW.SUBSCRIPT -> {
            if (pattern == PatternContext.BINDING_INTRODUCER && la().isInBindingPatternPosition()) {
                val m = mark()
                advance()
                return done(m, E.IDENTIFIER_PATTERN)
            }
            return parseIdentifierExpression(flavor)
        }
        KW.SELF_TYPE -> return parseIdentifierExpression(flavor)
        KW.ANY_TYPE -> {
            val m = mark()
            parseType()
            return done(m, E.TYPE_EXPR)
        }
        T.DOLLAR_IDENTIFIER -> return leaf(E.DECL_REFERENCE_EXPR)
        T.WILDCARD -> {
            if (atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) return parseIdentifierExpression(flavor)
            return leaf(E.DISCARD_ASSIGNMENT_EXPR)
        }
        T.POUND -> return parseMacroExpansion(E.MACRO_EXPANSION_EXPR, flavor)
        T.POUND_AVAILABLE, T.POUND_UNAVAILABLE -> {
            val m = mark()
            parseAvailabilityCondition()
            m.error("#available and #unavailable can only be used as conditions of 'if', 'guard' or 'while'")
            val r = mark()
            return done(r, E.MISSING_EXPR)
        }
        T.LBRACE -> return parseClosureExpression()
        T.DOT -> {
            val m = mark()
            advance()
            if (at(T.INTEGER_LITERAL)) {
                errorToken("'.' must be preceded by a digit in a floating point literal")
                return done(m, E.FLOAT_LITERAL_EXPR)
            }
            parseDeclReferenceExpr(allowKeywords = true)
            return done(m, E.MEMBER_ACCESS_EXPR)
        }
        KW.SUPER -> return leaf(E.SUPER_EXPR)
        T.LPAR -> return parseTupleExpression(pattern)
        T.LBRACKET -> return parseCollectionLiteral()
    }
    if (t is SwiftKeywordTokenType && t.isReserved && atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) {
        return parseIdentifierExpression(flavor)
    }
    if (flavor == ExprFlavor.ATTRIBUTE_ARGUMENTS && t is SwiftKeywordTokenType) {
        // `@attached(extension, ...)`, `@derivative(of: subscript)`: keywords naming declarations.
        val m = mark()
        bumpName(keepSpecialNames = true)
        return done(m, E.DECL_REFERENCE_EXPR)
    }
    error("Expected expression")
    return null
}

private fun SwiftParserCore.leaf(type: IElementType): Parsed {
    val m = mark()
    advance()
    return done(m, type)
}

private fun SwiftParserCore.parseIdentifierExpression(flavor: ExprFlavor): Parsed? {
    val ref = parseDeclReferenceExpr(allowKeywords = flavor == ExprFlavor.ATTRIBUTE_ARGUMENTS) ?: return null
    if (!la().canParseAsGenericArgumentList()) return ref
    val g = ref.marker.precede()
    parseGenericArguments()
    return done(g, E.GENERIC_SPECIALIZATION_EXPR)
}

// region Literals

internal fun SwiftParserCore.parseStringLiteral(): Parsed {
    val m = mark()
    if (at(T.AT)) errorToken("String literals in Swift are not preceded by an '@' sign")
    val hasRawDelimiter = consume(T.RAW_STRING_DELIMITER)
    val open = type()
    val multiline = open == T.MULTILINE_STRING_QUOTE
    if (open == T.STRING_QUOTE || open == T.MULTILINE_STRING_QUOTE || open == T.SINGLE_QUOTE) advance()
    var closed = false
    while (!eof()) {
        val t = type()
        if (t in T.STRING_CONTENT) {
            advance()
            continue
        }
        if (t == T.BACKSLASH) {
            val seg = mark()
            advance()
            consume(T.RAW_STRING_DELIMITER)
            if (expect(T.LPAR, "'(' in string interpolation")) {
                parseArgumentListElements(T.RPAR, PatternContext.NONE, ExprFlavor.BASIC)
                expect(T.RPAR, "')' in string interpolation")
            }
            seg.done(E.EXPRESSION_SEGMENT)
            continue
        }
        if (t == open && (multiline || !atStartOfLine())) {
            advance()
            closed = true
        }
        break
    }
    if (!closed) error(if (multiline) "Unterminated multi-line string literal" else "Unterminated string literal")
    if (hasRawDelimiter && closed && at(T.RAW_STRING_DELIMITER) && !hasTriviaBefore()) advance()
    return done(m, E.STRING_LITERAL_EXPR)
}

private fun SwiftParserCore.parseRegexLiteral(): Parsed {
    val m = mark()
    consume(T.REGEX_POUND_DELIMITER)
    expect(T.REGEX_SLASH, "'/'")
    consume(T.REGEX_PATTERN)
    if (!consume(T.REGEX_SLASH)) error("Unterminated regex literal")
    consume(T.REGEX_POUND_DELIMITER)
    return done(m, E.REGEX_LITERAL_EXPR)
}

// endregion

// region Tuples, collections, argument lists

private fun SwiftParserCore.parseTupleExpression(pattern: PatternContext): Parsed {
    val m = mark()
    advance() // (
    parseArgumentListElements(T.RPAR, pattern, ExprFlavor.BASIC)
    expect(T.RPAR, "')' to end tuple")
    return done(m, E.TUPLE_EXPR)
}

/** Parses `open` elements `close` as an ARGUMENT_LIST node. */
internal fun SwiftParserCore.parseArgumentList(open: IElementType, close: IElementType, pattern: PatternContext, flavor: ExprFlavor) {
    val m = mark()
    expect(open, if (open == T.LPAR) "'('" else "'['")
    parseArgumentListElements(close, pattern, flavor)
    expect(close, if (close == T.RPAR) "')' to end argument list" else "']' to end subscript")
    m.done(E.ARGUMENT_LIST)
}

private fun SwiftParserCore.atBinaryOperatorArgument(): Boolean =
    effectiveOperatorType() == T.BINARY_OPERATOR &&
        (type(operatorRunLength()) == T.COMMA || type(operatorRunLength()) == T.RPAR || type(operatorRunLength()) == T.RBRACKET)

/** Parses comma-separated LABELED_EXPR elements up to (not including) [close]. */
internal fun SwiftParserCore.parseArgumentListElements(close: IElementType, pattern: PatternContext, flavor: ExprFlavor) {
    if (at(close)) return
    while (!eof()) {
        val before = pos
        val el = mark()
        if (atArgumentLabel(allowDollar = true) && at(T.COLON, 1)) {
            bumpName()
            advance()
        } else if (at(T.COLON)) {
            advance()
        }
        if (atBinaryOperatorArgument()) {
            val op = mark()
            bumpOperator()
            op.done(E.DECL_REFERENCE_EXPR)
        } else if (!at(T.COMMA) && !at(close)) {
            parseExpression(flavor, pattern)
        } else {
            error("Expected expression")
        }
        el.done(E.LABELED_EXPR)
        if (consume(T.COMMA)) {
            if (at(close)) return
            continue
        }
        if (at(close) || eof()) return
        if (pos == before) {
            errorToken("Unexpected token")
            continue
        }
        // Missing comma between elements: recover if another element plausibly starts here.
        if (atStartOfLine() || at(T.RBRACE) || at(T.RPAR) || at(T.RBRACKET)) return
        error("Expected ','")
    }
}

private fun SwiftParserCore.parseCollectionLiteral(): Parsed {
    // `[4 of Int](repeating: 0)`: an InlineArray type in expression position.
    if (la(1).canParseStartOfInlineArrayTypeBody()) {
        val m = mark()
        parseType()
        return done(m, E.TYPE_EXPR)
    }
    val m = mark()
    advance() // [
    if (at(T.RBRACKET)) {
        advance()
        return done(m, E.ARRAY_EXPR)
    }
    if (at(T.COLON) && at(T.RBRACKET, 1)) {
        advance()
        advance()
        return done(m, E.DICTIONARY_EXPR)
    }
    var isDictionary: Boolean? = null
    while (!eof() && !at(T.RBRACKET)) {
        val before = pos
        val el = mark()
        if (at(T.POUND_IF)) {
            el.drop()
            parseIfConfig(IfConfigContext.COLLECTION_ELEMENTS)
            continue
        }
        parseExpression() ?: Unit
        if (isDictionary != false && at(T.COLON)) {
            isDictionary = true
            advance()
            parseExpression()
            el.done(E.DICTIONARY_ELEMENT)
        } else {
            if (isDictionary == true) error("Expected ':' in dictionary element")
            isDictionary = false
            el.drop()
        }
        if (consume(T.COMMA)) continue
        if (at(T.RBRACKET)) break
        if (pos == before) errorToken("Unexpected token in collection literal")
        else { error("Expected ',' in collection literal"); if (atStartOfLine() && !la().atStartOfExpression()) break }
    }
    expect(T.RBRACKET, "']' to end collection literal")
    return done(m, if (isDictionary == true) E.DICTIONARY_EXPR else E.ARRAY_EXPR)
}

// endregion

// region Closures and trailing closures

internal fun SwiftParserCore.parseTrailingClosures(flavor: ExprFlavor) {
    parseClosureExpression()
    while (la().atStartOfLabelledTrailingClosure()) {
        val m = mark()
        bumpName() // label
        advance() // :
        if (at(T.LBRACE)) parseClosureExpression() else parseExpression()
        m.done(E.TRAILING_CLOSURE)
    }
}

internal fun SwiftParserCore.parseClosureExpression(): Parsed {
    val m = mark()
    expect(T.LBRACE, "'{'")
    if (la().canParseClosureSignature()) parseClosureSignature()
    parseCodeBlockItems(BlockEnd.BRACE)
    expect(T.RBRACE, "'}' to end closure")
    return done(m, E.CLOSURE_EXPR)
}

private fun SwiftParserCore.parseClosureSignature() {
    val m = mark()
    parseAttributeList()
    if (at(T.LBRACKET)) {
        val cap = mark()
        advance()
        while (!eof() && !at(T.RBRACKET)) {
            val before = pos
            val c = mark()
            if (atKw("weak") || atKw("unowned")) {
                bumpKw()
                if (at(T.LPAR) && (atKw("safe", 1) || atKw("unsafe", 1)) && at(T.RPAR, 2)) {
                    advance(); bumpKw(); advance()
                }
            }
            if ((atIdentifier() || at(KW.SELF)) && at(T.EQ, 1)) {
                advance()
                val init = mark()
                advance()
                parseExpression()
                init.done(E.INITIALIZER_CLAUSE)
            } else {
                parseExpression()
            }
            c.done(E.CLOSURE_CAPTURE)
            if (consume(T.COMMA)) continue
            if (pos == before) errorToken("Unexpected token in capture list")
            else if (!at(T.RBRACKET)) error("Expected ',' in capture list")
            if (!at(T.RBRACKET) && atKw("in")) break
        }
        expect(T.RBRACKET, "']' to end capture list")
        cap.done(E.CLOSURE_CAPTURE_CLAUSE)
    }
    if (at(T.LPAR)) {
        val params = mark()
        advance()
        while (!eof() && !at(T.RPAR)) {
            val before = pos
            val p = mark()
            parseAttributeList()
            if (atArgumentLabel() || at(T.WILDCARD)) {
                bumpName()
                if (atArgumentLabel() || at(T.WILDCARD)) bumpName()
            } else {
                error("Expected parameter name")
            }
            if (consume(T.COLON)) {
                parseType()
                consumePunct("...", T.ELLIPSIS)
            }
            p.done(E.CLOSURE_PARAMETER)
            if (consume(T.COMMA)) continue
            if (pos == before) errorToken("Unexpected token in closure parameters")
            else if (!at(T.RPAR)) { error("Expected ','"); break }
        }
        expect(T.RPAR, "')'")
        params.done(E.CLOSURE_PARAMETER_CLAUSE)
    } else {
        while (at(T.IDENTIFIER) || at(T.WILDCARD)) {
            val p = mark()
            advance()
            if (at(T.COLON)) {
                advance()
                parseType()
            }
            p.done(E.CLOSURE_SHORTHAND_PARAMETER)
            if (!consume(T.COMMA)) break
        }
    }
    parseEffectSpecifiers(E.TYPE_EFFECT_SPECIFIERS)
    if (at(T.ARROW)) parseReturnClause()
    expect(KW.IN, "'in' after closure signature")
    m.done(E.CLOSURE_SIGNATURE)
}

// endregion

// region Key paths

private fun SwiftParserCore.parseKeyPathExpression(pattern: PatternContext): IElementType {
    val m = mark()
    advance() // \
    if (!at(T.DOT) && !at(T.POSTFIX_QUESTION) && !at(T.EXCLAMATION) && !at(T.LBRACKET)) {
        if (at(T.IDENTIFIER) || at(KW.SELF_TYPE) || at(KW.ANY_TYPE) || at(T.LPAR) || at(T.LBRACKET)) {
            parseSimpleType(stopAtFirstPeriod = true)
        }
    }
    while (true) {
        when {
            at(T.DOT) -> {
                val c = mark()
                advance()
                when {
                    at(T.POSTFIX_QUESTION) || at(T.EXCLAMATION) -> advance()
                    at(T.LBRACKET) -> parseArgumentList(T.LBRACKET, T.RBRACKET, pattern, ExprFlavor.BASIC)
                    at(T.INTEGER_LITERAL) -> {
                        // A tuple element: `\.0`.
                        val n = mark()
                        advance()
                        n.done(E.DECL_REFERENCE_EXPR)
                    }
                    else -> {
                        parseDeclReferenceExpr(allowKeywords = true)
                        if (at(T.LPAR) && !atStartOfLine()) parseArgumentList(T.LPAR, T.RPAR, pattern, ExprFlavor.BASIC)
                    }
                }
                c.done(E.KEY_PATH_COMPONENT)
            }
            (at(T.POSTFIX_QUESTION) || at(T.EXCLAMATION)) && !hasTriviaBefore() -> {
                val c = mark()
                advance()
                c.done(E.KEY_PATH_COMPONENT)
            }
            at(T.LBRACKET) && !atStartOfLine() && !hasTriviaBefore() -> {
                val c = mark()
                parseArgumentList(T.LBRACKET, T.RBRACKET, pattern, ExprFlavor.BASIC)
                c.done(E.KEY_PATH_COMPONENT)
            }
            else -> break
        }
    }
    m.done(E.KEY_PATH_EXPR)
    return E.KEY_PATH_EXPR
}

// endregion

// region Macro expansions

/** `#name<Generics>(args) { trailing }`, as an expression or a declaration. */
internal fun SwiftParserCore.parseMacroExpansion(type: IElementType, flavor: ExprFlavor, existing: PsiBuilder.Marker? = null): Parsed {
    val m = existing ?: mark()
    advance() // #
    if (!hasTriviaBefore() && (atIdentifierOrKeyword())) advance() else error("Expected macro name after '#'")
    if (la().canParseAsGenericArgumentList()) parseGenericArguments()
    if (at(T.LPAR) && !atStartOfLine()) {
        parseArgumentList(T.LPAR, T.RPAR, PatternContext.NONE, ExprFlavor.BASIC)
    }
    if (at(T.LBRACE) && la().atValidTrailingClosure(flavor) && flavor != ExprFlavor.STMT_CONDITION) {
        parseTrailingClosures(flavor)
    }
    return done(m, type)
}

// endregion

// region if / switch expressions

internal fun SwiftParserCore.parseIfExpression(): IElementType {
    val m = mark()
    advance() // if
    parseConditionList()
    parseCodeBlock()
    if (consume(KW.ELSE)) {
        if (at(KW.IF)) parseIfExpression() else parseCodeBlock()
    }
    m.done(E.IF_EXPR)
    return E.IF_EXPR
}

internal fun SwiftParserCore.parseSwitchExpression(): IElementType {
    val m = mark()
    advance() // switch
    parseExpression(ExprFlavor.STMT_CONDITION)
    if (expect(T.LBRACE, "'{' after switch subject")) {
        parseSwitchCases()
        expect(T.RBRACE, "'}' to end switch")
    }
    m.done(E.SWITCH_EXPR)
    return E.SWITCH_EXPR
}

// endregion
