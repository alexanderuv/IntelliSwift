package io.github.alexanderuv.intelliswift.parser

import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.KW
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/*
 * Lookahead predicates, ported from swift-syntax's `Lookahead.swift`, `Types.swift`,
 * `Declarations.swift`, `Statements.swift` and `Expressions.swift`. They never touch the
 * PsiBuilder; each works on a [Lookahead] cursor that callers copy freely.
 */

internal enum class ExprFlavor { BASIC, STMT_CONDITION, POUND_IF_DIRECTIVE, ATTRIBUTE_ARGUMENTS }

internal enum class PatternContext { NONE, MATCHING, BINDING_INTRODUCER }

internal val DECLARATION_MODIFIERS = setOf(
    "__consuming", "__setter_access", "_const", "_local", "async", "borrowing", "class", "consuming",
    "convenience", "distributed", "dynamic", "fileprivate", "final", "indirect", "infix", "internal",
    "isolated", "lazy", "mutating", "nonisolated", "nonmutating", "open", "optional", "override", "package",
    "postfix", "prefix", "private", "public", "reasync", "required", "rethrows", "sending", "static",
    "unowned", "weak",
)

internal val MODIFIERS_WITH_ARGUMENT = setOf(
    "fileprivate", "internal", "nonisolated", "package", "open", "private", "public", "unowned",
)

internal val CONTEXTUAL_DECL_KEYWORDS = setOf(
    "__consuming", "_compilerInitialized", "_const", "_local", "actor", "async", "convenience", "distributed",
    "dynamic", "final", "indirect", "infix", "isolated", "lazy", "mutating", "nonisolated", "nonmutating",
    "package", "open", "optional", "override", "postfix", "prefix", "required", "unowned", "weak",
)

internal val PURE_DECLARATION_KEYWORDS = setOf(
    "actor", "associatedtype", "case", "class", "deinit", "enum", "extension", "func", "import", "init", "macro",
    "operator", "precedencegroup", "protocol", "struct", "subscript", "typealias",
)

internal val BINDING_SPECIFIERS = setOf("let", "var", "inout", "_mutating", "_borrowing", "_consuming")

internal val EFFECT_SPECIFIERS = setOf("async", "reasync", "throws", "rethrows", "await", "throw", "try")

internal val TYPE_SPECIFIERS = setOf(
    "inout", "__shared", "__owned", "isolated", "_const", "borrowing", "consuming", "sending", "_resultDependsOn",
)
private val TYPE_SPECIFIERS_ARRAY = TYPE_SPECIFIERS.toTypedArray()
private val EFFECT_SPECIFIERS_ARRAY = EFFECT_SPECIFIERS.toTypedArray()

/** Keywords that may follow `.` in a type (`T.Type`, `P.Protocol`). */
internal fun Lookahead.atMetatypeKeyword(): Boolean = atKw("Type") || atKw("Protocol")

// region Attributes

/** Skips `@name`, `@name(args)` and `@name<T>`; returns whether any attribute was skipped. */
internal fun Lookahead.consumeAttributeList(): Boolean {
    var any = false
    while (at(T.AT)) {
        bump()
        if (!atIdentifierOrKeyword()) return any
        any = true
        bump()
        while (at(T.DOT) && atIdentifierOrKeyword(1)) bump(2)
        if (atPunct("<")) consumeGenericArguments()
        if (at(T.LPAR) && !hasTriviaBefore()) skipSingle()
    }
    return any
}

internal fun Lookahead.canParseCustomAttribute(): Boolean {
    if (!canParseTypeIdentifier()) return false
    if (at(T.LPAR) && !hasTriviaBefore()) skipSingle()
    return true
}

// endregion

// region Types

internal fun Lookahead.canParseType(): Boolean {
    consumeKw("repeat")
    if (!canParseTypeScalar()) return false
    consumePunct("...")
    return true
}

internal fun Lookahead.canParseIntegerLiteral(): Boolean {
    if (atText("-") && at(T.INTEGER_LITERAL, 1)) { bump(2); return true }
    return consume(T.INTEGER_LITERAL)
}

internal fun Lookahead.canParseValueGenericArgument(): Boolean = canParseIntegerLiteral()

internal fun Lookahead.canParseTypeAttributeList(): Boolean {
    while (true) {
        when {
            atAnyKw(*TYPE_SPECIFIERS_ARRAY) -> bump()
            atKw("nonisolated") -> {
                val ok = copy().run {
                    bump()
                    if (!at(T.LPAR) || atStartOfLine()) return@run true
                    bump()
                    if (!atKw("nonsending") || atStartOfLine()) return@run false
                    bump()
                    at(T.RPAR) && !atStartOfLine()
                }
                if (!ok) return false
                bump()
                if (at(T.LPAR) && !atStartOfLine()) skipSingle()
            }
            atKw("dependsOn") -> {
                if (atStartOfLine(1) || !at(T.LPAR, 1)) return false
                bump()
                skipSingle()
            }
            else -> break
        }
    }
    consumeAttributeList()
    return true
}

internal fun Lookahead.canParseTypeScalar(): Boolean {
    if (!canParseTypeAttributeList()) return false
    if (!canParseSimpleOrCompositionType()) return false
    if (canParseFunctionTypeArrow()) return canParseType()
    return true
}

internal fun Lookahead.canParseSimpleOrCompositionType(): Boolean {
    if (atKw("some") || atKw("any") || atKw("each")) bump()
    if (!canParseSimpleType()) return false
    while (atPunct("&")) {
        bump()
        if (!canParseSimpleType()) return false
    }
    return true
}

internal fun Lookahead.canParseSimpleType(): Boolean {
    when {
        at(KW.ANY_TYPE) -> bump()
        at(T.PREFIX_OPERATOR) -> {
            if (!atText("~")) return false
            bump()
            if (!canParseSimpleTypeHead()) return false
        }
        else -> if (!canParseSimpleTypeHead()) return false
    }
    while (true) {
        if (at(T.DOT)) {
            bump()
            if (atMetatypeKeyword()) { bump(); continue }
            if (canParseTypeIdentifier(allowKeyword = true)) continue
            return false
        }
        if ((at(T.POSTFIX_QUESTION) || at(T.EXCLAMATION)) && !atStartOfLine()) { bump(); continue }
        break
    }
    return true
}

private fun Lookahead.canParseSimpleTypeHead(): Boolean {
    when {
        at(KW.ANY_TYPE) -> bump()
        at(KW.SELF_TYPE) || at(T.IDENTIFIER) || at(T.COLON_COLON) -> return canParseTypeIdentifier()
        at(T.LPAR) -> { bump(); return canParseTupleBodyType() }
        at(T.LBRACKET) -> { bump(); return canParseCollectionTypeBody() }
        at(T.WILDCARD) -> bump()
        at(KW.REPEAT) -> return true
        else -> return false
    }
    return true
}

internal fun Lookahead.consumeModuleSelector(): Boolean {
    if (atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) { bump(2); return true }
    if (at(T.COLON_COLON)) { bump(); return true }
    return false
}

internal fun Lookahead.canParseTypeIdentifier(allowKeyword: Boolean = false): Boolean {
    consumeModuleSelector()
    if (at(KW.ANY_TYPE)) { bump(); return true }
    if (!(at(T.IDENTIFIER) || at(KW.SELF_TYPE) || (allowKeyword && atReservedKeyword()))) return false
    bump()
    if (atText("<") && !consumeGenericArguments()) return false
    return true
}

internal fun Lookahead.canParseStartOfInlineArrayTypeBody(): Boolean {
    val value = copy()
    if (value.canParseValueGenericArgument()) pos = value.pos else if (!canParseTypeScalar()) return false
    if (atKw("of") && !atStartOfLine()) { bump(); return true }
    return false
}

internal fun Lookahead.canParseInlineArrayTypeBody(): Boolean {
    if (!canParseStartOfInlineArrayTypeBody()) return false
    if (!canParseGenericArgument()) return false
    return consume(T.RBRACKET)
}

internal fun Lookahead.canParseCollectionTypeBody(): Boolean {
    val inline = copy()
    if (inline.canParseInlineArrayTypeBody()) { pos = inline.pos; return true }
    if (!canParseType()) return false
    if (consume(T.COLON)) {
        if (!canParseType()) return false
    }
    return consume(T.RBRACKET)
}

internal fun Lookahead.canParseTupleBodyType(): Boolean {
    if (at(T.RPAR) || at(T.RBRACE) || atPunct("...") || (!at(KW.INOUT) && copy().atStartOfDeclaration())) {
        return consume(T.RPAR)
    }
    do {
        consume(KW.INOUT)
        if (startsParameterName(isClosure = false)) {
            bump()
            if (atArgumentLabel()) bump()
            if (!consume(T.COLON)) return false
            if (!canParseType()) return false
            if (consume(T.EQ)) {
                while (!eof() && !at(T.RPAR) && !at(T.RBRACE) && !at(T.COMMA) && !atPunct("...") &&
                    !copy().atStartOfDeclaration()
                ) skipSingle()
            }
            continue
        }
        if (!canParseType()) return false
        consumePunct("...")
    } while (consume(T.COMMA) && !at(T.RPAR))
    return consume(T.RPAR)
}

internal fun Lookahead.canParseFunctionTypeArrow(): Boolean {
    if (consume(T.ARROW)) return true
    consumeEffectsSpecifiers()
    return consume(T.ARROW)
}

internal fun Lookahead.consumeEffectsSpecifiers() {
    while (atAnyKw(*EFFECT_SPECIFIERS_ARRAY)) {
        val isThrows = atKw("throws") || atKw("rethrows") || atKw("throw")
        bump()
        if (isThrows && at(T.LPAR)) {
            bump()
            canParseSimpleOrCompositionType()
            consume(T.RPAR)
        }
    }
}

internal fun Lookahead.canParseGenericArgument(): Boolean {
    val value = copy()
    if (value.canParseValueGenericArgument()) { pos = value.pos; return true }
    return canParseType()
}

/** Consumes `<...>` if it is a well-formed generic argument list. */
internal fun Lookahead.consumeGenericArguments(): Boolean {
    if (!atText("<")) return false
    bump()
    if (!atText(">")) {
        do {
            if (!canParseGenericArgument()) return false
        } while (consume(T.COMMA) && !atText(">"))
    }
    if (!atText(">")) return false
    bump()
    return true
}

internal fun Lookahead.canParseAsGenericArgumentList(): Boolean {
    if (!atText("<")) return false
    val la = copy()
    if (!la.consumeGenericArguments()) return false
    return la.isGenericTypeDisambiguatingToken()
}

internal fun Lookahead.isGenericTypeDisambiguatingToken(): Boolean = when (type()) {
    null, T.RPAR, T.RBRACKET, T.LBRACE, T.RBRACE, T.DOT, T.COMMA, T.SEMICOLON, T.EXCLAMATION,
    T.POSTFIX_QUESTION, T.COLON -> true
    T.BINARY_OPERATOR -> atText("&")
    T.LPAR, T.LBRACKET -> !atStartOfLine()
    else -> false
}

// endregion

// region Parameters

/** swift-syntax's `startsParameterName`. */
internal fun Lookahead.startsParameterName(isClosure: Boolean): Boolean {
    if (!(atArgumentLabel() || at(T.WILDCARD))) return false
    if (at(T.COLON, 1)) return true
    if (at(T.IDENTIFIER, 1) || at(T.WILDCARD, 1) || (atArgumentLabel(k = 1) && !atReservedKeyword(1))) {
        if (atKw("isolated") || atKw("some") || atKw("any") || atKw("each") || atKw("sending")) {
            // `isolated x: T` and friends: the first word is a specifier, not a name.
            return !at(T.COLON, 1) && at(T.COLON, 2)
        }
        return at(T.COLON, 2) || isClosure
    }
    if (atArgumentLabel(k = 1) && at(T.COLON, 2)) return true
    return false
}

// endregion

// region Declarations and statements

/** Skips attributes and modifiers; returns (hasAttribute, hasModifier). */
internal fun Lookahead.skipAttributesAndModifiers(): Pair<Boolean, Boolean> {
    var hasAttribute = false
    while (true) {
        if (at(T.AT)) {
            consumeAttributeList()
            hasAttribute = true
        } else if (at(T.POUND_IF) && copy().let { la -> la.consumeIfConfigOfAttributes().also { if (it) pos = la.pos } }) {
            hasAttribute = true
        } else {
            break
        }
    }
    var hasModifier = false
    while (true) {
        val t = type() ?: break
        if (!(t == T.IDENTIFIER || t is SwiftKeywordTokenType)) break
        val name = text().toString()
        if (name !in DECLARATION_MODIFIERS || name == "class") break
        hasModifier = true
        bump()
        if (name != "open" && at(T.LPAR) && name in MODIFIERS_WITH_ARGUMENT) {
            bump()
            skipUntil(T.RPAR)
            consume(T.RPAR)
        }
    }
    return hasAttribute to hasModifier
}

/** `#if` blocks that contain only attributes, as in `#if os(macOS) @available(...) #endif func f()`. */
internal fun Lookahead.consumeIfConfigOfAttributes(): Boolean {
    if (!at(T.POUND_IF)) return false
    var sawAttributes = false
    do {
        bump() // #if / #elseif / #else
        while (!eof() && !atStartOfLine()) skipSingle() // the condition
        while (true) {
            if (at(T.AT)) {
                sawAttributes = true
                consumeAttributeList()
            } else if (at(T.POUND_IF)) {
                val before = pos
                if (consumeIfConfigOfAttributes()) sawAttributes = true
                if (pos == before) break
            } else {
                break
            }
        }
    } while (at(T.POUND_ELSEIF) || at(T.POUND_ELSE))
    return sawAttributes && atStartOfLine() && consume(T.POUND_ENDIF)
}

internal fun Lookahead.atStartOfDeclaration(allowInitDecl: Boolean = true, requiresDecl: Boolean = false): Boolean {
    val (hasAttribute, hasModifier) = skipAttributesAndModifiers()
    if (hasAttribute) {
        if (at(T.RBRACE) || eof() || at(T.POUND_ENDIF)) return true
        if (at(T.STRING_QUOTE)) return false
    }
    val t = type()
    if (t is SwiftKeywordTokenType || t == T.IDENTIFIER || t == T.POUND) {
        val word = if (t == T.POUND) "#" else text().toString()
        val isKeyword = t is SwiftKeywordTokenType && t.isReserved
        when {
            word == "actor" && t == T.IDENTIFIER -> return atStartOfActor(allowInitDecl, requiresDecl)
            word == "case" && isKeyword -> return requiresDecl
            word == "init" && isKeyword -> return allowInitDecl
            word == "macro" && t == T.IDENTIFIER -> return at(T.IDENTIFIER, 1)
            word == "#" -> return hasAttribute || hasModifier || requiresDecl
            word == "using" && t == T.IDENTIFIER -> return false
            isKeyword && (word in PURE_DECLARATION_KEYWORDS || word in BINDING_SPECIFIERS) -> return true
            t == T.IDENTIFIER && word in BINDING_SPECIFIERS -> return true
        }
        if (t == T.IDENTIFIER && word in CONTEXTUAL_DECL_KEYWORDS) {
            bump()
            return atStartOfDeclaration(allowInitDecl, requiresDecl)
        }
    }
    if (requiresDecl) {
        if (hasAttribute || hasModifier) return true
    }
    if (at(KW.TRY) && (atKw("let", 1) || atKw("var", 1))) return true
    return false
}

private fun Lookahead.atStartOfActor(allowInitDecl: Boolean, requiresDecl: Boolean): Boolean {
    if (at(T.IDENTIFIER, 1)) return true
    val la = copy()
    do { la.bump() } while (la.copy().atStartOfDeclaration(allowInitDecl, requiresDecl) && !la.eof())
    return la.at(T.IDENTIFIER)
}

internal fun Lookahead.atStartOfStatement(preferExpr: Boolean): Boolean {
    if (at(T.IDENTIFIER) && at(T.COLON, 1)) bump(2)
    val t = type()
    if (t is SwiftKeywordTokenType && t.isReserved) {
        when (t.keyword) {
            "return", "throw", "defer", "if", "guard", "while", "do", "for", "break", "continue",
            "fallthrough", "switch" -> return true
            "repeat" -> return at(T.LBRACE, 1)
            "try" -> {
                if (!preferExpr && !(atKw("do", 1) || atKw("if", 1) || atKw("switch", 1))) {
                    bump()
                    return atStartOfStatement(preferExpr)
                }
                return false
            }
        }
        return false
    }
    if (atKw("yield") || atKw("discard")) {
        return atContextualKeywordPrefixedSyntax(ExprFlavor.BASIC, preferPostfixExpr = true)
    }
    return false
}

internal fun Lookahead.atStartOfSwitchCase(): Boolean {
    val la = copy()
    val hasAttribute = la.consumeAttributeList()
    if (hasAttribute && la.at(T.RBRACE)) return true
    return la.at(KW.CASE) || la.at(KW.DEFAULT)
}

internal fun Lookahead.atStartOfConditionalSwitchCases(): Boolean {
    if (!at(T.POUND_IF)) return atStartOfSwitchCase()
    val la = copy()
    do {
        la.bump()
        while (!la.eof() && !la.atStartOfLine()) la.skipSingle()
    } while (la.at(T.POUND_IF) || la.at(T.POUND_ELSEIF) || la.at(T.POUND_ELSE))
    return la.atStartOfSwitchCase()
}

/**
 * After an initializer, `{ didSet ... }` / `{ willSet ... }` / `{ init ... }` (possibly after attributes)
 * is an accessor block, not a trailing closure. `get` and `set` cannot follow an initializer, so
 * they need no check (swift-syntax `atStartOfGetSetAccessor`).
 */
internal fun Lookahead.atStartOfGetSetAccessor(): Boolean {
    if (!at(T.LBRACE)) return false
    if (atKw("didSet", 1) || atKw("willSet", 1) || at(KW.INIT, 1)) return true
    if (!at(T.AT, 1)) return false
    bump() // {
    if (atText("_accessorBlock", 1)) return true
    while (consume(T.AT)) {
        if (!consume(T.IDENTIFIER)) return false
        if (at(T.LPAR)) skipSingle()
    }
    return atKw("didSet") || atKw("willSet") || at(KW.INIT)
}

// endregion

// region Expressions

internal fun Lookahead.atStartOfExpression(): Boolean {
    if (atIdentifierOrKeyword() && at(T.COLON_COLON, 1)) return true
    val t = type() ?: return false
    if (t == T.IDENTIFIER && atAnyKw("await", "_move", "_borrow", "borrow", "consume", "copy", "each", "any", "unsafe")) {
        return true
    }
    if (t == KW.TRY || t == KW.REPEAT) {
        val la = copy()
        la.bump()
        if (la.at(KW.DO) || la.at(KW.IF) || la.at(KW.SWITCH)) return true
        if (la.copy().atStartOfDeclaration() || la.copy().atStartOfStatement(preferExpr = false)) return la.atStartOfLine()
        return true
    }
    when (t) {
        T.BACKSLASH, T.PREFIX_AMP, T.PREFIX_OPERATOR -> return true
        KW.ANY_TYPE, KW.SELF_TYPE, T.COLON_COLON, KW.DEINIT, T.DOLLAR_IDENTIFIER, KW.FALSE, T.FLOAT_LITERAL,
        T.IDENTIFIER, KW.INIT, T.INTEGER_LITERAL, T.LBRACE, T.LPAR, T.LBRACKET, KW.NIL, T.DOT, T.POUND,
        T.POUND_AVAILABLE, T.POUND_UNAVAILABLE, T.REGEX_SLASH, T.REGEX_POUND_DELIMITER, KW.SELF, KW.SUBSCRIPT,
        KW.SUPER, KW.TRUE, T.WILDCARD, T.RAW_STRING_DELIMITER, T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE,
        T.SINGLE_QUOTE, KW.DO, KW.IF, KW.SWITCH -> return true
    }
    if (t == T.AT && at(T.STRING_QUOTE, 1)) return true
    return false
}

/** swift-syntax's `atContextualKeywordPrefixedSyntax`: is the contextual keyword here an operator on what follows? */
internal fun Lookahead.atContextualKeywordPrefixedSyntax(
    flavor: ExprFlavor,
    acceptClosure: Boolean = false,
    preferPostfixExpr: Boolean = true,
    allowNextLineOperand: Boolean = false,
): Boolean {
    if (atStartOfLine(1) && !allowNextLineOperand) return false
    val next = type(1) ?: return false
    when (next) {
        T.IDENTIFIER, T.DOLLAR_IDENTIFIER, T.WILDCARD -> return true
        T.INTEGER_LITERAL, T.FLOAT_LITERAL, T.STRING_QUOTE, T.MULTILINE_STRING_QUOTE, T.SINGLE_QUOTE,
        T.RAW_STRING_DELIMITER, T.REGEX_SLASH, T.REGEX_POUND_DELIMITER -> return true
        T.PREFIX_AMP, T.PREFIX_OPERATOR, T.AT, T.BACKSLASH, T.POUND -> return true
        T.BINARY_OPERATOR, T.EQ, T.ARROW, T.INFIX_QUESTION -> return false
        T.POSTFIX_OPERATOR, T.POSTFIX_QUESTION, T.EXCLAMATION -> return false
        T.RBRACE, T.RPAR, T.RBRACKET, T.COLON, T.COMMA, T.SEMICOLON, T.POUND_ELSE, T.POUND_ELSEIF, T.POUND_ENDIF -> return false
        T.COLON_COLON, T.STRING_SEGMENT, T.REGEX_PATTERN, T.BACKTICK, T.POUND_AVAILABLE, T.POUND_UNAVAILABLE,
        T.POUND_SOURCE_LOCATION, T.POUND_IF, T.SHEBANG -> return false
        T.DOT, T.LPAR, T.LBRACKET -> {
            if (preferPostfixExpr) return false
            return tokens.hasTriviaAfter(pos)
        }
        T.LBRACE -> {
            if (!acceptClosure) return false
            val la = copy()
            la.bump()
            return la.atValidTrailingClosure(flavor)
        }
    }
    if (next is SwiftKeywordTokenType) {
        return when (next.keyword) {
            "Any", "Self", "self", "super", "init", "true", "false", "nil", "repeat", "try", "if", "switch" -> true
            else -> false
        }
    }
    return false
}

internal fun Lookahead.atValidTrailingClosure(flavor: ExprFlavor): Boolean {
    if (!at(T.LBRACE)) return false
    if (copy().atStartOfGetSetAccessor()) return false
    if (at(KW.CASE, 1)) return false
    if (flavor != ExprFlavor.STMT_CONDITION) return true
    if (atStartOfLine(1)) return false
    val la = copy()
    la.bump()
    while (!la.eof() && !la.at(T.RBRACE) && !la.at(T.POUND_ENDIF) && !la.at(T.POUND_ELSE) && !la.at(T.POUND_ELSEIF)) {
        la.skipSingle()
    }
    if (!la.consume(T.RBRACE)) return false
    return when (la.type()) {
        T.LBRACE, KW.WHERE, T.COMMA -> true
        T.LBRACKET, T.LPAR, T.DOT, KW.IS, KW.AS, T.POSTFIX_QUESTION, T.INFIX_QUESTION, T.EXCLAMATION, T.COLON,
        T.EQ, T.POSTFIX_OPERATOR, T.BINARY_OPERATOR -> !la.atStartOfLine()
        else -> false
    }
}

internal fun Lookahead.atStartOfLabelledTrailingClosure(): Boolean {
    if (!atArgumentLabel() || at(KW.DEFAULT) || !at(T.COLON, 1)) return false
    return at(T.LBRACE, 2) || (at(T.IDENTIFIER, 2) && text(2).startsWith("<#"))
}

internal fun Lookahead.canParseClosureSignature(): Boolean {
    val la = copy()
    var sawTopLevelArrow = false
    while (la.consume(T.AT)) {
        if (!la.at(T.IDENTIFIER)) break
        la.canParseCustomAttribute()
    }
    if (la.consume(T.LBRACKET)) {
        la.skipUntil(T.RBRACKET)
        if (!la.consume(T.RBRACKET)) return false
    }
    if (la.at(T.LPAR)) {
        while (!la.eof() && !la.at(T.RBRACE) && !la.at(KW.IN) && !la.at(T.ARROW)) la.skipSingle()
    } else if (la.at(T.IDENTIFIER) || la.at(T.WILDCARD)) {
        la.bump()
        while (true) {
            if (la.consume(T.COLON)) {
                if (!la.canParseType()) break
            }
            if (!la.consume(T.COMMA)) break
            if (la.at(T.IDENTIFIER) || la.at(T.WILDCARD)) { la.bump(); continue }
            return false
        }
    }
    la.consume(T.RPAR)
    la.consumeEffectsSpecifiers()
    if (la.consume(T.ARROW)) {
        if (!la.canParseType()) return false
        sawTopLevelArrow = true
        la.consumeEffectsSpecifiers()
    }
    if (la.at(KW.IN)) return true
    return sawTopLevelArrow
}

/** In a `case let` pattern: is the identifier here a new binding rather than the start of an expression? */
internal fun Lookahead.isInBindingPatternPosition(): Boolean {
    if (atText("<", 1)) {
        val la = copy()
        la.bump()
        return !la.canParseAsGenericArgumentList()
    }
    return when (type(1)) {
        T.DOT, T.LPAR, T.LBRACKET -> false
        else -> true
    }
}

/** `borrowing` / `_borrowing` only introduce a binding when a name follows on the same line. */
internal fun Lookahead.shouldParsePatternBinding(): Boolean {
    if (atKw("borrowing") || atKw("_borrowing")) {
        return (at(T.IDENTIFIER, 1) || at(T.WILDCARD, 1)) && !atStartOfLine(1)
    }
    return true
}

internal val PATTERN_BINDING_INTRODUCERS = setOf("let", "var", "inout", "_mutating", "_borrowing", "_consuming", "borrowing")

// endregion
