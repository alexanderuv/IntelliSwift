package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.PsiBuilder
import io.github.alexanderuv.intelliswift.lexer.KW
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/* Types and patterns, following swift-syntax's `Types.swift` and `Patterns.swift`. */

// region Types

/** Parses a type; reports an error and produces a MISSING_TYPE if there is none. */
internal fun SwiftParserCore.parseType(): Boolean {
    if (at(KW.REPEAT)) {
        val m = mark()
        advance()
        parseType()
        m.done(E.PACK_EXPANSION_TYPE)
        return true
    }
    val m = mark()
    val attributed = parseTypeSpecifiersAndAttributes()
    val function = mark()
    val ok = parseSimpleOrCompositionType()
    if (ok && la().canParseFunctionTypeArrowHere()) {
        // A function type: the type parsed so far is its parameter tuple.
        parseEffectSpecifiers(E.TYPE_EFFECT_SPECIFIERS)
        parseReturnClauseArrowOnly()
        function.done(E.FUNCTION_TYPE)
    } else {
        function.drop()
    }
    if (attributed) m.done(E.ATTRIBUTED_TYPE) else m.drop()
    return ok
}

private fun Lookahead.canParseFunctionTypeArrowHere(): Boolean {
    if (at(T.ARROW)) return true
    if (!(atKw("async") || atKw("throws") || atKw("rethrows") || atKw("reasync") || atKw("await") || atKw("throw"))) return false
    consumeEffectsSpecifiers()
    return at(T.ARROW)
}

private fun SwiftParserCore.parseReturnClauseArrowOnly() {
    expect(T.ARROW, "'->'")
    parseType()
}

/** Type specifiers (`inout`, `borrowing`, `sending`, ...) and type attributes (`@escaping`, `@Sendable`). */
private fun SwiftParserCore.parseTypeSpecifiersAndAttributes(): Boolean {
    var any = false
    while (true) {
        when {
            atAnyKw(*TYPE_SPECIFIER_ARRAY) && !at(T.COLON, 1) && !(at(T.DOT, 1)) -> {
                // `isolated(any)` is an attribute-like specifier on function types.
                bumpKw()
                any = true
            }
            atKw("nonisolated") && at(T.LPAR, 1) && atKw("nonsending", 2) -> {
                bumpKw()
                advance()
                bumpKw()
                expect(T.RPAR, "')'")
                any = true
            }
            atKw("dependsOn") && at(T.LPAR, 1) && !atStartOfLine(1) -> {
                bumpKw()
                skipBalanced()
                any = true
            }
            at(T.AT) -> {
                parseAttribute()
                any = true
            }
            else -> return any
        }
    }
}

private val TYPE_SPECIFIER_ARRAY = TYPE_SPECIFIERS.toTypedArray()

private fun SwiftParserCore.parseSimpleOrCompositionType(): Boolean {
    val m = mark()
    val ok: Boolean
    if (atKw("some") || atKw("any")) {
        val s = mark()
        bumpKw()
        ok = parseSimpleType()
        s.done(E.SOME_OR_ANY_TYPE)
    } else if (atKw("each")) {
        val s = mark()
        bumpKw()
        ok = parseSimpleType()
        s.done(E.PACK_ELEMENT_TYPE)
    } else {
        ok = parseSimpleType()
    }
    if (!ok || !atPunct("&")) {
        m.drop()
        return ok
    }
    while (consumePunct("&")) {
        if (atKw("some") || atKw("any")) {
            val s = mark()
            bumpKw()
            parseSimpleType()
            s.done(E.SOME_OR_ANY_TYPE)
        } else {
            parseSimpleType()
        }
    }
    m.done(E.COMPOSITION_TYPE)
    return true
}

internal fun SwiftParserCore.parseSimpleType(stopAtFirstPeriod: Boolean = false): Boolean {
    var m = mark()
    when {
        at(T.PREFIX_OPERATOR) && atText("~") -> {
            advance()
            parseSimpleType()
            m.done(E.SUPPRESSED_TYPE)
            return true
        }
        at(T.IDENTIFIER) || at(KW.SELF_TYPE) || at(KW.ANY_TYPE) || at(T.COLON_COLON) || at(T.WILDCARD) ||
            (atReservedKeyword() && at(T.COLON_COLON, 1)) -> {
            parseTypeIdentifierTail()
            m.done(E.IDENTIFIER_TYPE)
        }
        at(T.LPAR) -> {
            parseTupleType()
            m.done(E.TUPLE_TYPE)
        }
        at(T.LBRACKET) -> {
            val kind = parseCollectionType()
            m.done(kind)
        }
        at(KW.CLASS) -> {
            advance()
            m.done(E.CLASS_RESTRICTION_TYPE)
        }
        else -> {
            m.drop()
            error("Expected type")
            return false
        }
    }
    while (true) {
        when {
            at(T.DOT) && !stopAtFirstPeriod -> {
                val outer = m.precede()
                advance()
                if (atKw("Type") || atKw("Protocol")) {
                    bumpKw()
                    outer.done(E.METATYPE_TYPE)
                } else {
                    if (atIdentifierOrKeyword()) advance() else error("Expected member name")
                    if (atText("<")) parseGenericArguments()
                    outer.done(E.MEMBER_TYPE)
                }
                m = outer
            }
            at(T.POSTFIX_QUESTION) && !atStartOfLine() -> {
                val outer = m.precede()
                advance()
                outer.done(E.OPTIONAL_TYPE)
                m = outer
            }
            at(T.EXCLAMATION) && !atStartOfLine() -> {
                val outer = m.precede()
                advance()
                outer.done(E.IMPLICITLY_UNWRAPPED_OPTIONAL_TYPE)
                m = outer
            }
            else -> return true
        }
    }
}

/** The name and generic arguments of an IDENTIFIER_TYPE, including a module selector `M::Name`. */
private fun SwiftParserCore.parseTypeIdentifierTail() {
    if (atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) {
        advance()
        advance()
    } else if (at(T.COLON_COLON)) {
        advance()
    }
    if (atIdentifierOrKeyword() || at(T.WILDCARD)) advance() else error("Expected type name")
    if (atText("<")) parseGenericArguments()
}

private fun SwiftParserCore.parseTupleType() {
    advance() // (
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        val el = mark()
        if (at(KW.INOUT) && la(1).startsParameterName(isClosure = false)) bumpKw()
        if (la().startsParameterName(isClosure = false)) {
            bumpName()
            if (atArgumentLabel() || at(T.WILDCARD)) bumpName()
            expect(T.COLON, "':'")
        }
        parseType()
        consumePunct("...", T.ELLIPSIS)
        if (at(T.EQ)) {
            val init = mark()
            advance()
            parseExpression()
            init.error("Default argument not permitted in a tuple type")
        }
        el.done(E.TUPLE_TYPE_ELEMENT)
        if (consume(T.COMMA)) continue
        if (pos == before) errorToken("Unexpected token in tuple type")
        else if (!at(T.RPAR)) { error("Expected ',' in tuple type"); break }
    }
    expect(T.RPAR, "')' to end tuple type")
}

private fun SwiftParserCore.parseCollectionType(): com.intellij.psi.tree.IElementType {
    advance() // [
    if (la().canParseInlineArrayTypeBody()) {
        parseGenericArgument()
        bumpKw() // of
        parseGenericArgument()
        expect(T.RBRACKET, "']'")
        return E.INLINE_ARRAY_TYPE
    }
    parseType()
    val kind = if (consume(T.COLON)) {
        parseType()
        E.DICTIONARY_TYPE
    } else {
        E.ARRAY_TYPE
    }
    expect(T.RBRACKET, "']' to end type")
    return kind
}

internal fun SwiftParserCore.parseGenericArguments() {
    val m = mark()
    if (atText("<")) bumpAs(T.LANGLE) else error("Expected '<'")
    if (!atText(">")) {
        while (!eof()) {
            val before = pos
            parseGenericArgument()
            if (consume(T.COMMA)) {
                if (atText(">")) break
                continue
            }
            if (pos == before) break
            break
        }
    }
    if (atText(">")) bumpAs(T.RANGLE) else error("Expected '>' to end generic argument list")
    m.done(E.GENERIC_ARGUMENT_CLAUSE)
}

private fun SwiftParserCore.parseGenericArgument() {
    val m = mark()
    if (atText("-") && at(T.INTEGER_LITERAL, 1)) {
        val e = mark()
        val op = mark()
        // After a split `<` the `-` piece is left-bound; swift-syntax consumes it as a prefix operator.
        bumpAs(T.PREFIX_OPERATOR)
        op.done(E.OPERATOR_REFERENCE)
        val lit = mark()
        advance()
        lit.done(E.INTEGER_LITERAL_EXPR)
        e.done(E.PREFIX_OPERATOR_EXPR)
    } else if (at(T.INTEGER_LITERAL)) {
        val lit = mark()
        advance()
        lit.done(E.INTEGER_LITERAL_EXPR)
    } else {
        parseType()
    }
    m.done(E.GENERIC_ARGUMENT)
}

/** `async`, `throws`, `throws(E)`, `rethrows`, `reasync`; misplaced `await`/`try`/`throw` are errors. */
internal fun SwiftParserCore.parseEffectSpecifiers(nodeType: com.intellij.psi.tree.IElementType): Boolean {
    val m = mark()
    var any = false
    while (true) {
        when {
            atKw("async") || atKw("reasync") -> bumpKw()
            atKw("throws") || atKw("rethrows") -> {
                val c = mark()
                val typed = atKw("throws")
                bumpKw()
                if (typed && at(T.LPAR) && !atStartOfLine()) {
                    advance()
                    parseType()
                    expect(T.RPAR, "')'")
                }
                c.done(E.THROWS_CLAUSE)
            }
            atKw("await") || atKw("try") || atKw("throw") -> {
                if (!(at(T.ARROW, 1) || atKw("async", 1) || atKw("throws", 1) || at(T.LBRACE, 1))) break
                errorToken("'${text()}' is not a valid effect specifier here; did you mean '${if (atKw("await")) "async" else "throws"}'?")
            }
            else -> break
        }
        any = true
    }
    if (any) m.done(nodeType) else m.drop()
    return any
}

internal fun SwiftParserCore.parseReturnClause() {
    val m = mark()
    expect(T.ARROW, "'->'")
    parseType()
    m.done(E.RETURN_CLAUSE)
}

internal fun SwiftParserCore.parseTypeAnnotation() {
    val m = mark()
    expect(T.COLON, "':'")
    parseType()
    m.done(E.TYPE_ANNOTATION)
}

/** Skips a balanced parenthesized group starting at `(`. */
internal fun SwiftParserCore.skipBalanced() {
    val close = when (type()) {
        T.LPAR -> T.RPAR
        T.LBRACKET -> T.RBRACKET
        T.LBRACE -> T.RBRACE
        else -> { advance(); return }
    }
    advance()
    var depth = 1
    while (!eof() && depth > 0) {
        val t = type()
        if (t == T.LPAR || t == T.LBRACKET || t == T.LBRACE) depth++
        if (t == T.RPAR || t == T.RBRACKET || t == T.RBRACE) depth--
        if (depth == 0 && t != close) break
        advance()
    }
}

// endregion

// region Patterns

/** Patterns of `let`/`var` declarations, `for` loops and closure-free contexts (swift-syntax `parsePattern`). */
internal fun SwiftParserCore.parsePattern(): Boolean {
    val t = type()
    val m = mark()
    when {
        t == T.WILDCARD -> {
            advance()
            m.done(E.WILDCARD_PATTERN)
        }
        t == T.IDENTIFIER || (t is SwiftKeywordTokenType && (t.keyword == "init" || t.keyword == "self")) -> {
            advance()
            m.done(E.IDENTIFIER_PATTERN)
        }
        t == T.LPAR -> {
            advance()
            while (!eof() && !at(T.RPAR)) {
                val before = pos
                val el = mark()
                if (atArgumentLabel() && at(T.COLON, 1)) {
                    bumpName()
                    advance()
                }
                parsePattern()
                el.done(E.TUPLE_PATTERN_ELEMENT)
                if (consume(T.COMMA)) continue
                if (pos == before) errorToken("Unexpected token in tuple pattern")
                else if (!at(T.RPAR)) { error("Expected ','"); break }
            }
            expect(T.RPAR, "')'")
            m.done(E.TUPLE_PATTERN)
        }
        t != null && atAnyKw(*PATTERN_BINDING_ARRAY) && !at(T.COLON, 1) -> {
            bumpKw()
            parsePattern()
            m.done(E.VALUE_BINDING_PATTERN)
        }
        else -> {
            m.drop()
            if (t is SwiftKeywordTokenType && t.isReserved && !atStartOfLine() && !at(T.LBRACE)) {
                // A keyword used as a variable name: swift-syntax recovers by treating it as the name.
                errorToken("Keyword '${text()}' cannot be used as an identifier here")
                return true
            }
            error("Expected pattern")
            return false
        }
    }
    return true
}

private val PATTERN_BINDING_ARRAY = arrayOf("let", "var", "inout", "_mutating", "_borrowing", "_consuming")

/** Patterns of `case` labels, `if case` and `catch` (swift-syntax `parseMatchingPattern`). */
internal fun SwiftParserCore.parseMatchingPattern(context: PatternContext): com.intellij.psi.tree.IElementType? {
    if (at(KW.IS)) {
        val m = mark()
        advance()
        parseType()
        m.done(E.IS_TYPE_PATTERN)
        return E.IS_TYPE_PATTERN
    }
    if (atAnyKw(*PATTERN_INTRODUCER_ARRAY) && la().shouldParsePatternBinding()) {
        val m = mark()
        bumpKw()
        parseMatchingPattern(PatternContext.BINDING_INTRODUCER)
        m.done(E.VALUE_BINDING_PATTERN)
        return E.VALUE_BINDING_PATTERN
    }
    val m: PsiBuilder.Marker = mark()
    // Patterns are followed by `{` (catch), `:` (case) or `=` (if case), never a trailing closure.
    val inner = parseSequenceExpression(ExprFlavor.STMT_CONDITION, context)
    if (inner == null) {
        m.drop()
        return null
    }
    if (inner == E.IDENTIFIER_PATTERN || inner == E.PATTERN_EXPR) {
        m.drop()
        return inner
    }
    m.done(E.EXPRESSION_PATTERN)
    return E.EXPRESSION_PATTERN
}

private val PATTERN_INTRODUCER_ARRAY = PATTERN_BINDING_INTRODUCERS.toTypedArray()

// endregion
