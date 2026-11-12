package io.github.alexanderuv.intelliswift.parser

import io.github.alexanderuv.intelliswift.lexer.KW
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/* Statements, code blocks and `#if`, following swift-syntax's `Statements.swift`, `TopLevel.swift` and `Directives.swift`. */

internal enum class BlockEnd { EOF, BRACE, IF_CONFIG_CLAUSE, SWITCH_CASE }

private fun SwiftParserCore.atBlockEnd(end: BlockEnd): Boolean {
    if (eof()) return true
    return when (end) {
        BlockEnd.EOF -> false
        BlockEnd.BRACE -> at(T.RBRACE)
        BlockEnd.IF_CONFIG_CLAUSE -> at(T.POUND_ELSEIF) || at(T.POUND_ELSE) || at(T.POUND_ENDIF) || at(T.RBRACE)
        BlockEnd.SWITCH_CASE -> at(T.RBRACE) || at(T.POUND_ELSEIF) || at(T.POUND_ELSE) || at(T.POUND_ENDIF) ||
            la().atStartOfConditionalSwitchCases()
    }
}

/** Parses statements, declarations and expressions until [end]. */
internal fun SwiftParserCore.parseCodeBlockItems(end: BlockEnd, allowInitDecl: Boolean = true) {
    while (!atBlockEnd(end)) {
        if (end == BlockEnd.EOF && at(T.RBRACE)) {
            errorToken("Extraneous '}' at top level")
            continue
        }
        val before = pos
        parseCodeBlockItem(allowInitDecl, end)
        while (consume(T.SEMICOLON)) Unit
        if (pos == before) {
            if (at(T.RBRACE) || at(T.RPAR) || at(T.RBRACKET)) {
                if (end == BlockEnd.EOF || end == BlockEnd.IF_CONFIG_CLAUSE) {
                    errorToken("Unexpected '${text()}'")
                    continue
                }
                if (at(T.RBRACE)) return
                errorToken("Unexpected '${text()}'")
                continue
            }
            errorToken("Unexpected token '${text()}'")
        }
    }
}

private fun SwiftParserCore.parseCodeBlockItem(allowInitDecl: Boolean, end: BlockEnd) {
    when {
        at(T.POUND_IF) -> {
            if (la().atStartOfConditionalAttributes()) parseDeclaration() else parseIfConfig(IfConfigContext.CODE_BLOCK_ITEMS)
        }
        at(T.POUND_SOURCE_LOCATION) -> parsePoundSourceLocation()
        la().atStartOfDeclaration(allowInitDecl = allowInitDecl) -> parseDeclaration()
        la().atStartOfStatement(preferExpr = false) -> parseStatement()
        la().atStartOfExpression() -> parseExpression()
        la().atStartOfSwitchCase() -> {
            val m = mark()
            parseSwitchCase()
            m.error("'case' label can only appear inside a 'switch' statement")
        }
        (at(T.AT) && atIdentifierOrKeyword(1)) || atModifierStart() -> parseDeclaration()
        else -> {
            // swift-syntax wraps unexpected tokens into a missing declaration. Consume up to the next line.
            if (at(T.SEMICOLON)) return
            if (at(T.RBRACE) || at(T.RPAR) || at(T.RBRACKET)) return
            if (end != BlockEnd.EOF && (at(T.POUND_ELSE) || at(T.POUND_ELSEIF) || at(T.POUND_ENDIF))) return
            val m = mark()
            advance()
            while (!eof() && !atStartOfLine() && !at(T.RBRACE) && !at(T.SEMICOLON)) skipItem()
            m.error("Expected expression, statement or declaration")
        }
    }
}

private fun SwiftParserCore.atModifierStart(): Boolean {
    val t = type() ?: return false
    if (t != T.IDENTIFIER && t !is io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType) return false
    val word = text().toString()
    return word in DECLARATION_MODIFIERS && word != "class"
}

/** Skips one token or a balanced bracket group. */
internal fun SwiftParserCore.skipItem() {
    if (at(T.LPAR) || at(T.LBRACKET) || at(T.LBRACE)) skipBalanced() else advance()
}

internal fun SwiftParserCore.parseCodeBlock() {
    val m = mark()
    if (!at(T.LBRACE)) {
        error("Expected '{'")
        m.drop()
        return
    }
    advance()
    parseCodeBlockItems(BlockEnd.BRACE)
    expect(T.RBRACE, "'}'")
    m.done(E.CODE_BLOCK)
}

internal fun SwiftParserCore.parseSourceFile() {
    if (at(T.SHEBANG)) advance()
    parseCodeBlockItems(BlockEnd.EOF)
}

// region Statements

internal fun SwiftParserCore.parseStatement() {
    if (at(T.IDENTIFIER) && at(T.COLON, 1)) {
        val m = mark()
        advance()
        advance()
        if (la().atStartOfStatement(preferExpr = false)) parseStatement() else error("Expected statement after label")
        m.done(E.LABELED_STMT)
        return
    }
    when (type()) {
        KW.RETURN -> simpleStatement(E.RETURN_STMT) {
            if (!atStartOfLine() && !at(T.RBRACE) && !at(T.SEMICOLON) && !eof() && la().atStartOfExpression() &&
                !(at(T.POUND_ENDIF) || at(T.POUND_ELSE) || at(T.POUND_ELSEIF))
            ) parseExpression()
        }
        KW.THROW -> simpleStatement(E.THROW_STMT) { parseExpression() }
        KW.BREAK -> simpleStatement(E.BREAK_STMT) { if (at(T.IDENTIFIER) && !atStartOfLine()) advance() }
        KW.CONTINUE -> simpleStatement(E.CONTINUE_STMT) { if (at(T.IDENTIFIER) && !atStartOfLine()) advance() }
        KW.FALLTHROUGH -> simpleStatement(E.FALLTHROUGH_STMT) {}
        KW.DEFER -> simpleStatement(E.DEFER_STMT) { parseCodeBlock() }
        KW.IF -> parseIfExpression()
        KW.SWITCH -> parseSwitchExpression()
        KW.GUARD -> simpleStatement(E.GUARD_STMT) {
            parseConditionList()
            expect(KW.ELSE, "'else' after 'guard' condition")
            parseCodeBlock()
        }
        KW.WHILE -> simpleStatement(E.WHILE_STMT) {
            parseConditionList()
            parseCodeBlock()
        }
        KW.REPEAT -> simpleStatement(E.REPEAT_STMT) {
            parseCodeBlock()
            if (expect(KW.WHILE, "'while' after 'repeat' body")) parseExpression(ExprFlavor.BASIC)
        }
        KW.FOR -> parseForStatement()
        KW.DO -> parseDoStatement()
        KW.TRY -> parseExpression()
        else -> when {
            atKw("yield") -> simpleStatement(E.YIELD_STMT, keyword = true) {
                if (at(T.LPAR)) {
                    parseArgumentList(T.LPAR, T.RPAR, PatternContext.NONE, ExprFlavor.BASIC)
                } else {
                    parseExpression()
                }
            }
            atKw("discard") -> simpleStatement(E.DISCARD_STMT, keyword = true) { parseExpression() }
            else -> parseExpression()
        }
    }
}

private inline fun SwiftParserCore.simpleStatement(type: com.intellij.psi.tree.IElementType, keyword: Boolean = false, body: () -> Unit) {
    val m = mark()
    if (keyword) bumpKw() else advance()
    body()
    m.done(type)
}

private fun SwiftParserCore.parseForStatement() {
    val m = mark()
    advance() // for
    if (atKw("try") && !at(T.COLON, 1)) bumpKw()
    if (atKw("await") && !at(T.COLON, 1)) bumpKw()
    if (atKw("unsafe") && !at(KW.IN, 1)) bumpKw()
    if (consume(KW.CASE)) {
        parseMatchingPattern(PatternContext.MATCHING)
    } else {
        parsePattern()
    }
    if (at(T.COLON)) parseTypeAnnotation()
    if (expect(KW.IN, "'in' after for-each pattern")) {
        parseExpression(ExprFlavor.STMT_CONDITION)
    }
    if (at(KW.WHERE)) parseWhereClause()
    parseCodeBlock()
    m.done(E.FOR_STMT)
}

internal fun SwiftParserCore.parseWhereClause() {
    val m = mark()
    advance() // where
    parseExpression(ExprFlavor.STMT_CONDITION)
    m.done(E.WHERE_CLAUSE)
}

private fun SwiftParserCore.parseDoStatement() {
    val m = mark()
    advance() // do
    if (atKw("throws")) parseEffectSpecifiers(E.THROWS_CLAUSE)
    parseCodeBlock()
    while (at(KW.CATCH)) {
        val c = mark()
        advance()
        if (!at(T.LBRACE)) {
            while (true) {
                val item = mark()
                parseMatchingPattern(PatternContext.MATCHING)
                if (at(KW.WHERE)) parseWhereClause()
                item.done(E.CATCH_ITEM)
                if (!consume(T.COMMA)) break
            }
        }
        parseCodeBlock()
        c.done(E.CATCH_CLAUSE)
    }
    m.done(E.DO_STMT)
}

/** The comma-separated conditions of `if`, `guard` and `while`. */
internal fun SwiftParserCore.parseConditionList() {
    while (true) {
        val before = pos
        parseCondition()
        if (consume(T.COMMA)) continue
        // swift-syntax recovers from `&&` and `where` used instead of a comma.
        if ((atPunct("&&") || at(KW.WHERE)) && !at(T.LBRACE)) {
            errorToken("Expected ',' joining parts of a multi-clause condition")
            continue
        }
        if (pos == before && !at(T.LBRACE) && !eof()) errorToken("Expected condition")
        break
    }
}

private fun SwiftParserCore.parseCondition() {
    when {
        at(T.POUND_AVAILABLE) || at(T.POUND_UNAVAILABLE) -> parseAvailabilityCondition()
        at(KW.CASE) -> {
            val m = mark()
            advance()
            parseMatchingPattern(PatternContext.MATCHING)
            if (at(T.COLON)) parseTypeAnnotation()
            if (at(T.EQ)) {
                val init = mark()
                advance()
                parseExpression(ExprFlavor.STMT_CONDITION)
                init.done(E.INITIALIZER_CLAUSE)
            } else {
                error("Expected '=' in pattern matching")
            }
            m.done(E.MATCHING_PATTERN_CONDITION)
        }
        atAnyKw("let", "var", "inout", "borrowing", "_mutating", "_borrowing", "_consuming") &&
            !(atKw("borrowing") && !at(T.IDENTIFIER, 1)) -> {
            val m = mark()
            bumpKw()
            parsePattern()
            if (at(T.COLON)) parseTypeAnnotation()
            if (at(T.EQ)) {
                val init = mark()
                advance()
                parseExpression(ExprFlavor.STMT_CONDITION)
                init.done(E.INITIALIZER_CLAUSE)
            }
            m.done(E.OPTIONAL_BINDING_CONDITION)
        }
        else -> parseExpression(ExprFlavor.STMT_CONDITION)
    }
}

internal fun SwiftParserCore.parseAvailabilityCondition() {
    val m = mark()
    advance() // #available / #unavailable
    if (expect(T.LPAR, "'('")) {
        parseAvailabilityArguments()
        expect(T.RPAR, "')'")
    }
    m.done(E.AVAILABILITY_CONDITION)
}

private val AVAILABILITY_KEYWORDS = arrayOf(
    "deprecated", "unavailable", "noasync", "introduced", "obsoleted", "message", "renamed",
)

/** Availability arguments: `iOS 13.0`, `*`, `swift 5.9`, `deprecated`, `message: "..."`. */
internal fun SwiftParserCore.parseAvailabilityArguments() {
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        val m = mark()
        if (atAnyKw(*AVAILABILITY_KEYWORDS) && (at(T.COLON, 1) || at(T.COMMA, 1) || at(T.RPAR, 1))) bumpKw()
        while (!eof() && !at(T.COMMA) && !at(T.RPAR)) {
            if (at(T.LPAR) || at(T.LBRACKET) || at(T.LBRACE)) skipBalanced()
            else if (at(T.STRING_QUOTE) || at(T.RAW_STRING_DELIMITER) || at(T.MULTILINE_STRING_QUOTE)) parseStringLiteral()
            else advance()
        }
        m.done(E.AVAILABILITY_ARGUMENT)
        if (!consume(T.COMMA)) break
        if (pos == before) break
    }
}

// endregion

// region Switch

internal fun SwiftParserCore.parseSwitchCases() {
    while (!eof() && !at(T.RBRACE)) {
        val before = pos
        when {
            at(T.POUND_IF) -> parseIfConfig(IfConfigContext.SWITCH_CASES)
            la().atStartOfSwitchCase() -> parseSwitchCase()
            else -> {
                val m = mark()
                parseCodeBlockItems(BlockEnd.SWITCH_CASE)
                m.error("All statements inside a switch must be covered by a 'case' or 'default' label")
            }
        }
        if (pos == before) errorToken("Unexpected token in switch")
    }
}

internal fun SwiftParserCore.parseSwitchCase() {
    val m = mark()
    parseAttributeList()
    if (at(KW.DEFAULT)) {
        val l = mark()
        advance()
        expect(T.COLON, "':' after 'default'")
        l.done(E.SWITCH_DEFAULT_LABEL)
    } else {
        val l = mark()
        expect(KW.CASE, "'case'")
        while (true) {
            val item = mark()
            parseMatchingPattern(PatternContext.MATCHING)
            if (at(KW.WHERE)) parseWhereClause()
            item.done(E.SWITCH_CASE_ITEM)
            if (!consume(T.COMMA)) break
        }
        expect(T.COLON, "':' after case pattern")
        l.done(E.SWITCH_CASE_LABEL)
    }
    parseCodeBlockItems(BlockEnd.SWITCH_CASE)
    m.done(E.SWITCH_CASE)
}

// endregion

// region #if and #sourceLocation

internal enum class IfConfigContext { CODE_BLOCK_ITEMS, MEMBERS, SWITCH_CASES, POSTFIX_EXPRESSION, ATTRIBUTES, COLLECTION_ELEMENTS }

/** `#if` as an attribute-only block before a declaration. */
internal fun Lookahead.atStartOfConditionalAttributes(): Boolean {
    val la = copy()
    if (!la.consumeIfConfigOfAttributes()) return false
    return la.atStartOfDeclaration()
}

internal fun SwiftParserCore.parseIfConfig(context: IfConfigContext) {
    val m = mark()
    while (at(T.POUND_IF) || at(T.POUND_ELSEIF) || at(T.POUND_ELSE)) {
        val clause = mark()
        val isElse = at(T.POUND_ELSE)
        advance()
        if (!isElse) {
            if (atStartOfLine()) error("Expected condition in conditional compilation clause")
            else parseExpression(ExprFlavor.POUND_IF_DIRECTIVE)
        }
        when (context) {
            IfConfigContext.CODE_BLOCK_ITEMS -> parseCodeBlockItems(BlockEnd.IF_CONFIG_CLAUSE)
            IfConfigContext.MEMBERS -> parseMemberItems(BlockEnd.IF_CONFIG_CLAUSE)
            IfConfigContext.SWITCH_CASES -> {
                while (!eof() && !at(T.POUND_ELSEIF) && !at(T.POUND_ELSE) && !at(T.POUND_ENDIF) && !at(T.RBRACE)) {
                    val before = pos
                    if (at(T.POUND_IF)) parseIfConfig(IfConfigContext.SWITCH_CASES)
                    else if (la().atStartOfSwitchCase()) parseSwitchCase()
                    else parseCodeBlockItems(BlockEnd.SWITCH_CASE)
                    if (pos == before) errorToken("Unexpected token")
                }
            }
            IfConfigContext.POSTFIX_EXPRESSION -> {
                if (at(T.DOT)) {
                    val head = mark()
                    advance()
                    if (at(T.INTEGER_LITERAL) || at(KW.SELF)) advance() else parseDeclReferenceExpr(allowKeywords = true)
                    head.done(E.MEMBER_ACCESS_EXPR)
                    parsePostfixExpressionSuffix(Parsed(head, E.MEMBER_ACCESS_EXPR), ExprFlavor.BASIC, PatternContext.NONE)
                } else if (at(T.POUND_IF)) {
                    parseIfConfig(IfConfigContext.POSTFIX_EXPRESSION)
                }
            }
            IfConfigContext.ATTRIBUTES -> parseAttributeList()
            IfConfigContext.COLLECTION_ELEMENTS -> {
                while (!eof() && !at(T.POUND_ELSEIF) && !at(T.POUND_ELSE) && !at(T.POUND_ENDIF) && !at(T.RBRACKET)) {
                    val before = pos
                    parseExpression()
                    if (at(T.COLON)) { advance(); parseExpression() }
                    consume(T.COMMA)
                    if (pos == before) errorToken("Unexpected token")
                }
            }
        }
        clause.done(E.IF_CONFIG_CLAUSE)
    }
    expect(T.POUND_ENDIF, "'#endif' in conditional compilation block")
    m.done(E.IF_CONFIG_DECL)
}

internal fun SwiftParserCore.parsePoundSourceLocation() {
    val m = mark()
    advance()
    if (expect(T.LPAR, "'('")) {
        parseArgumentListElements(T.RPAR, PatternContext.NONE, ExprFlavor.BASIC)
        expect(T.RPAR, "')'")
    }
    m.done(E.POUND_SOURCE_LOCATION)
}

// endregion
