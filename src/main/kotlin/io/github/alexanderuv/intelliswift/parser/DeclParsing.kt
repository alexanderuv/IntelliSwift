package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.WhitespacesAndCommentsBinder
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.KW
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

/* Declarations, following swift-syntax's `Declarations.swift`, `Nominals.swift`, `Attributes.swift` and `Modifiers.swift`. */

internal fun SwiftParserCore.parseDeclaration() {
    val m = mark()
    parseAttributesAndModifiers()
    val t = type()
    val word = text().toString()
    val reserved = t is SwiftKeywordTokenType && t.isReserved
    when {
        reserved && word == "import" -> parseImport(m)
        reserved && word == "class" -> parseNominal(m, E.CLASS_DECL)
        reserved && word == "struct" -> parseNominal(m, E.STRUCT_DECL)
        reserved && word == "enum" -> parseNominal(m, E.ENUM_DECL)
        reserved && word == "protocol" -> parseNominal(m, E.PROTOCOL_DECL)
        reserved && word == "extension" -> parseExtension(m)
        t == T.IDENTIFIER && word == "actor" -> parseNominal(m, E.ACTOR_DECL)
        reserved && word == "func" -> parseFunction(m)
        reserved && word == "init" -> parseInitializer(m)
        reserved && word == "deinit" -> parseDeinitializer(m)
        reserved && word == "subscript" -> parseSubscript(m)
        reserved && word == "typealias" -> parseTypeAlias(m)
        reserved && word == "associatedtype" -> parseAssociatedType(m)
        reserved && word == "case" -> parseEnumCase(m)
        reserved && word == "operator" -> parseOperatorDecl(m)
        reserved && word == "precedencegroup" -> parsePrecedenceGroup(m)
        t == T.IDENTIFIER && word == "macro" -> parseMacroDecl(m)
        t == T.POUND -> parseMacroExpansion(E.MACRO_EXPANSION_DECL, ExprFlavor.BASIC, m)
        word in BINDING_SPECIFIERS || (reserved && word == "try" && (atKw("let", 1) || atKw("var", 1))) -> parseVariable(m)
        // Callers parse a `#if` block themselves, so one here follows attributes or modifiers
        // (`public #if ...`). As in swift-syntax, those form a missing declaration and the `#if`
        // is left to the caller as the next item.
        else -> {
            error("Expected declaration")
            m.done(E.MISSING_DECL)
        }
    }
    m.setCustomEdgeTokenBinders(DocCommentBinder, null)
}

/**
 * Binds the doc comments (`///`, `/** */`) directly above a declaration to it, so the comment is
 * part of the declaration's PSI. A blank line, a plain comment, or a comment that ends a line of
 * code stops the run; those stay outside, as PsiBuilder's default binder would leave them.
 */
internal object DocCommentBinder : WhitespacesAndCommentsBinder {
    override fun getEdgePosition(tokens: List<IElementType>, atStreamEdge: Boolean, getter: WhitespacesAndCommentsBinder.TokenTextGetter): Int {
        var result = tokens.size
        var i = tokens.size - 1
        while (i >= 0) {
            val t = tokens[i]
            if (t == T.WHITE_SPACE) {
                if (newlines(getter.get(i)) >= 2) break
                i--
                continue
            }
            if (t != T.DOC_LINE_COMMENT && t != T.DOC_BLOCK_COMMENT) break
            val startsLine = if (i == 0) atStreamEdge else tokens[i - 1] == T.WHITE_SPACE && newlines(getter.get(i - 1)) > 0
            if (!startsLine) break
            result = i
            i--
        }
        return result
    }

    private fun newlines(text: CharSequence): Int {
        var n = 0
        var k = 0
        while (k < text.length) {
            val c = text[k]
            if (c == '\n') n++
            else if (c == '\r') { n++; if (k + 1 < text.length && text[k + 1] == '\n') k++ }
            k++
        }
        return n
    }
}

// region Attributes and modifiers

/**
 * Parses attributes and modifiers into a MODIFIER_LIST. Returns false if there were none.
 * A `#if` block of attributes is included.
 */
internal fun SwiftParserCore.parseAttributesAndModifiers(): Boolean {
    val list = mark()
    var any = false
    while (true) {
        if (at(T.AT)) { parseAttribute(); any = true; continue }
        if (at(T.POUND_IF) && la().copy().let { it.consumeIfConfigOfAttributes() }) {
            parseIfConfig(IfConfigContext.ATTRIBUTES)
            any = true
            continue
        }
        if (parseModifier()) { any = true; continue }
        break
    }
    if (any) list.done(E.MODIFIER_LIST) else list.drop()
    return any
}

/**
 * Attributes and the few modifiers a parameter may carry (swift-syntax `parseParameterModifiers`).
 * Words such as `consuming` or `isolated` followed by a name and `:` are argument labels, not modifiers.
 */
private fun SwiftParserCore.parseParameterAttributesAndModifiers() {
    val list = mark()
    var any = parseAttributeList()
    while (atAnyKw("isolated", "_const") && !at(T.COLON, 1) && !at(T.COLON, 2) && atIdentifierOrKeyword(1)) {
        val m = mark()
        bumpKw()
        m.done(E.DECL_MODIFIER)
        any = true
    }
    if (any) list.done(E.MODIFIER_LIST) else list.drop()
}

internal fun SwiftParserCore.parseAttributeList(): Boolean {
    var any = false
    while (at(T.AT)) {
        parseAttribute()
        any = true
    }
    return any
}

/** Attributes whose arguments are not expressions; their arguments are kept as a token group. */
private val RAW_ARGUMENT_ATTRIBUTES = setOf(
    "objc", "_objcRuntimeName", "_objcImplementation", "convention", "_effects", "derivative", "differentiable", "transpose", "_implements", "_dynamicReplacement", "inline",
    "_expose", "abi", "_documentation", "_private", "_alignment", "_optimize", "_nonSendable", "exclusivity",
    "_spi", "_typeEraser", "_projectedValueProperty", "storageRestrictions", "extern",
    "_rawLayout", "_swift_native_objc_runtime_base", "_unavailableFromAsync", "isolated", "_noMetadata",
    "_opaqueReturnTypeOf", "_backDeploy", "_cdecl", "_silgen_name", "_semantics", "_section", "section",
    "_used", "used", "_extern", "_clangImporterSynthesizedType",
)

private val AVAILABILITY_ATTRIBUTES = setOf("available", "_spi_available", "backDeployed", "_originallyDefinedIn")

internal fun SwiftParserCore.parseAttribute() {
    val m = mark()
    advance() // @
    val name = text().toString()
    if (atIdentifierOrKeyword() && !hasTriviaBefore()) {
        val nameMarker = mark()
        val typeMarker = mark()
        advance()
        while (at(T.DOT) && atIdentifierOrKeyword(1) && !hasTriviaBefore()) {
            advance()
            advance()
        }
        if (atText("<") && !hasTriviaBefore()) parseGenericArguments()
        typeMarker.done(E.IDENTIFIER_TYPE)
        nameMarker.drop()
    } else {
        error("Expected attribute name")
    }
    if (at(T.LPAR) && !hasTriviaBefore()) {
        val args = mark()
        when (name) {
            in AVAILABILITY_ATTRIBUTES -> {
                advance()
                parseAvailabilityArguments()
                expect(T.RPAR, "')'")
            }
            "_specialize", "specialize", "specialized" -> {
                advance()
                parseSpecializeArguments()
                expect(T.RPAR, "')'")
            }
            "_lifetime", "lifetime" -> {
                advance()
                parseLifetimeArguments()
                expect(T.RPAR, "')'")
            }
            in RAW_ARGUMENT_ATTRIBUTES -> skipBalanced()
            else -> {
                advance()
                parseArgumentListElements(T.RPAR, PatternContext.NONE, ExprFlavor.ATTRIBUTE_ARGUMENTS)
                expect(T.RPAR, "')'")
            }
        }
        args.done(E.ATTRIBUTE_ARGUMENTS)
    }
    m.done(E.ATTRIBUTE)
}

/** `@_specialize(exported: true, kind: full, where T == Int)`: labeled arguments and a generic where clause. */
private fun SwiftParserCore.parseSpecializeArguments() {
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        if (at(KW.WHERE)) {
            parseGenericWhereClause()
        } else {
            val m = mark()
            while (!eof() && !at(T.COMMA) && !at(T.RPAR) && !at(KW.WHERE)) skipItem()
            m.done(E.LABELED_EXPR)
        }
        if (!consume(T.COMMA) && (pos == before || !at(KW.WHERE))) break
    }
}

/** `@_lifetime(target: borrow source, copy other, &inoutParam)`. */
private fun SwiftParserCore.parseLifetimeArguments() {
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        val m = mark()
        if (atArgumentLabel() && at(T.COLON, 1)) {
            bumpName()
            advance()
        }
        if (atAnyKw("borrow", "copy", "inout") && !at(T.COMMA, 1) && !at(T.RPAR, 1)) bumpKw()
        if (at(T.PREFIX_AMP)) advance()
        while (!eof() && !at(T.COMMA) && !at(T.RPAR)) skipItem()
        m.done(E.LABELED_EXPR)
        if (!consume(T.COMMA) || pos == before) break
    }
}

/** Parses one declaration modifier if the cursor is at one. */
private fun SwiftParserCore.parseModifier(): Boolean {
    val t = type() ?: return false
    if (t != T.IDENTIFIER && t !is SwiftKeywordTokenType) return false
    val word = text().toString()
    if (word !in DECLARATION_MODIFIERS) return false
    if (word == "class") {
        // `class func`, `class var`, ...: a modifier. `class Foo`: a declaration.
        val next = type(1)
        if (next == T.IDENTIFIER && !(text(1).toString() in DECLARATION_MODIFIERS || text(1).toString() == "actor")) return false
        if (next == null || next == T.LBRACE || next == T.COLON) return false
    } else if (t == T.IDENTIFIER) {
        // A contextual modifier must be followed by something that continues the declaration.
        val la = la(1)
        val continues = la.copy().atStartOfDeclaration() || la.atIdentifierOrKeyword() || la.at(T.LPAR) || la.at(T.AT) ||
            la.at(T.POUND)
        if (!continues) return false
        if (la.at(T.LPAR) && word !in MODIFIERS_WITH_ARGUMENT && !la.copy().let { it.bump(); false }) {
            // e.g. `optional(...)` is a call, not a modifier.
            return false
        }
    }
    val m = mark()
    bumpKw()
    if (at(T.LPAR) && word in MODIFIERS_WITH_ARGUMENT && word != "open" && !atStartOfLine()) {
        advance()
        if (atIdentifierOrKeyword()) advance() else error("Expected modifier argument")
        expect(T.RPAR, "')'")
    }
    m.done(E.DECL_MODIFIER)
    return true
}

// endregion

// region Imports

private fun SwiftParserCore.parseImport(m: PsiBuilder.Marker) {
    advance() // import
    if (atAnyKw("typealias", "struct", "class", "enum", "protocol", "var", "let", "func", "inout") && !at(T.DOT, 1)) bumpKw()
    val path = mark()
    while (true) {
        when {
            atIdentifierOrKeyword() -> advance()
            type()?.let { TokenCursor.isOperatorLike(it) } == true && !at(T.DOT) -> bumpOperatorName()
            else -> { error("Expected module name"); break }
        }
        if (at(T.DOT) && !atStartOfLine()) { advance(); continue }
        break
    }
    path.done(E.IMPORT_PATH)
    m.done(E.IMPORT_DECL)
}

// endregion

// region Nominal types and extensions

private fun SwiftParserCore.parseNominal(m: PsiBuilder.Marker, type: IElementType) {
    bumpKw()
    if (atIdentifier()) advance() else expectIdentifierRecovering("type name")
    if (type == E.PROTOCOL_DECL && atText("<")) parsePrimaryAssociatedTypes()
    if (type != E.PROTOCOL_DECL && atText("<")) parseGenericParameterClause()
    if (at(T.COLON)) parseInheritanceClause()
    if (at(KW.WHERE)) parseGenericWhereClause()
    parseMemberBlock()
    m.done(type)
}

private fun SwiftParserCore.expectIdentifierRecovering(what: String) {
    val t = type()
    if (t is SwiftKeywordTokenType && t.isReserved && !atStartOfLine() && !at(T.LBRACE)) {
        errorToken("Keyword '${text()}' cannot be used as an identifier here")
        return
    }
    error("Expected $what")
}

private fun SwiftParserCore.parseExtension(m: PsiBuilder.Marker) {
    advance() // extension
    parseType()
    if (at(T.COLON)) parseInheritanceClause()
    if (at(KW.WHERE)) parseGenericWhereClause()
    parseMemberBlock()
    m.done(E.EXTENSION_DECL)
}

private fun SwiftParserCore.parsePrimaryAssociatedTypes() {
    val m = mark()
    bumpAs(T.LANGLE)
    while (!eof() && !atText(">")) {
        val p = mark()
        expectIdentifier("primary associated type name")
        p.done(E.PRIMARY_ASSOCIATED_TYPE)
        if (!consume(T.COMMA)) break
    }
    if (atText(">")) bumpAs(T.RANGLE) else error("Expected '>'")
    m.done(E.PRIMARY_ASSOCIATED_TYPE_CLAUSE)
}

internal fun SwiftParserCore.parseInheritanceClause() {
    val m = mark()
    advance() // :
    while (true) {
        val item = mark()
        if (at(KW.CLASS)) {
            val c = mark()
            advance()
            c.done(E.CLASS_RESTRICTION_TYPE)
        } else {
            parseType()
        }
        item.done(E.INHERITED_TYPE)
        if (!consume(T.COMMA)) break
    }
    m.done(E.INHERITANCE_CLAUSE)
}

internal fun SwiftParserCore.parseMemberBlock() {
    val m = mark()
    if (!expect(T.LBRACE, "'{' in declaration")) {
        m.drop()
        return
    }
    parseMemberItems(BlockEnd.BRACE)
    expect(T.RBRACE, "'}' to end declaration body")
    m.done(E.MEMBER_BLOCK)
}

internal fun SwiftParserCore.parseMemberItems(end: BlockEnd) {
    while (!eof() && !at(T.RBRACE)) {
        if (end == BlockEnd.IF_CONFIG_CLAUSE && (at(T.POUND_ELSEIF) || at(T.POUND_ELSE) || at(T.POUND_ENDIF))) return
        val before = pos
        when {
            at(T.SEMICOLON) -> advance()
            at(T.POUND_IF) && !la().atStartOfConditionalAttributes() -> parseIfConfig(IfConfigContext.MEMBERS)
            at(T.POUND_SOURCE_LOCATION) -> parsePoundSourceLocation()
            la().atStartOfDeclaration(requiresDecl = true) -> parseDeclaration()
            else -> {
                val err = mark()
                advance()
                while (!eof() && !at(T.RBRACE) && !(atStartOfLine() && la().atStartOfDeclaration(requiresDecl = true))) skipItem()
                err.error("Unexpected code in declaration body; expected a declaration")
            }
        }
        if (pos == before) errorToken("Unexpected token")
    }
}

// endregion

// region Generics

internal fun SwiftParserCore.parseGenericParameterClause() {
    val m = mark()
    bumpAs(T.LANGLE)
    while (!eof() && !atText(">")) {
        val before = pos
        val p = mark()
        parseAttributeList()
        if ((atKw("each") || atKw("let")) && at(T.IDENTIFIER, 1)) bumpKw()
        expectIdentifier("generic parameter name")
        if (at(T.COLON)) {
            advance()
            parseType()
        }
        p.done(E.GENERIC_PARAMETER)
        if (consume(T.COMMA)) continue
        if (at(KW.WHERE)) {
            parseGenericWhereClause()
            break
        }
        if (pos == before) errorToken("Unexpected token in generic parameter list")
        else if (!atText(">")) { error("Expected ',' or '>'"); break }
    }
    if (atText(">")) bumpAs(T.RANGLE) else error("Expected '>' to end generic parameter list")
    m.done(E.GENERIC_PARAMETER_CLAUSE)
}

internal fun SwiftParserCore.parseGenericWhereClause() {
    val m = mark()
    advance() // where
    while (true) {
        val r = mark()
        if (at(T.INTEGER_LITERAL) || (atText("-") && at(T.INTEGER_LITERAL, 1))) {
            if (atText("-")) advance()
            advance()
        } else {
            parseType()
        }
        when {
            at(T.COLON) -> {
                advance()
                if (atAnyKw("_Trivial", "_TrivialAtMost", "_UnknownLayout", "_RefCountedObject", "_NativeRefCountedObject",
                        "_Class", "_NativeClass", "_BridgeObject", "_TrivialStride")
                ) {
                    bumpKw()
                    if (at(T.LPAR)) skipBalanced()
                } else {
                    parseType()
                }
            }
            atPunct("==") -> {
                advance()
                if (at(T.INTEGER_LITERAL) || (atText("-") && at(T.INTEGER_LITERAL, 1))) {
                    if (atText("-")) advance()
                    advance()
                } else {
                    parseType()
                }
            }
            else -> error("Expected ':' or '==' to indicate a conformance or same-type requirement")
        }
        r.done(E.GENERIC_REQUIREMENT)
        if (!consume(T.COMMA)) break
    }
    m.done(E.GENERIC_WHERE_CLAUSE)
}

// endregion

// region Functions

private fun SwiftParserCore.parseFunction(m: PsiBuilder.Marker) {
    advance() // func
    val t = type()
    when {
        t == T.IDENTIFIER -> advance()
        t != null && TokenCursor.isOperatorLike(t) -> bumpOperatorName()
        else -> expectIdentifierRecovering("function name")
    }
    if (atText("<")) parseGenericParameterClause()
    parseFunctionSignature(allowReturn = true)
    if (at(KW.WHERE)) parseGenericWhereClause()
    if (at(T.LBRACE)) parseCodeBlock()
    m.done(E.FUNCTION_DECL)
}

internal fun SwiftParserCore.parseFunctionSignature(allowReturn: Boolean) {
    val m = mark()
    parseParameterClause()
    parseEffectSpecifiers(E.FUNCTION_EFFECT_SPECIFIERS)
    if (allowReturn && at(T.ARROW)) parseReturnClause()
    m.done(E.FUNCTION_SIGNATURE)
}

internal fun SwiftParserCore.parseParameterClause() {
    val m = mark()
    if (!expect(T.LPAR, "'(' in parameter list")) {
        m.drop()
        return
    }
    // As in swift-syntax: the list goes on while parameters end with a comma, and stops at a
    // parameter that consumes nothing. `expect` then recovers to the `)`.
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        val p = mark()
        parseParameterAttributesAndModifiers()
        if (atArgumentLabel() || at(T.WILDCARD)) {
            bumpName()
            if ((atArgumentLabel() || at(T.WILDCARD)) && !at(T.COLON)) bumpName()
        } else {
            error("Expected parameter name")
        }
        if (expect(T.COLON, "':' and type for parameter")) parseType()
        consumePunct("...", T.ELLIPSIS)
        if (at(T.EQ)) parseInitializerClause()
        if (discardIfEmpty(p, before)) break
        p.done(E.FUNCTION_PARAMETER)
        if (!consume(T.COMMA)) break
    }
    expect(T.RPAR, "')' to end parameter list")
    m.done(E.PARAMETER_CLAUSE)
}

internal fun SwiftParserCore.parseInitializerClause() {
    val m = mark()
    advance() // =
    parseExpression()
    m.done(E.INITIALIZER_CLAUSE)
}

private fun SwiftParserCore.parseInitializer(m: PsiBuilder.Marker) {
    advance() // init
    if (at(T.POSTFIX_QUESTION) || at(T.EXCLAMATION) || (atPunct("?") && !hasTriviaBefore())) advance()
    if (atText("<")) parseGenericParameterClause()
    parseFunctionSignature(allowReturn = true)
    if (at(KW.WHERE)) parseGenericWhereClause()
    if (at(T.LBRACE)) parseCodeBlock()
    m.done(E.INITIALIZER_DECL)
}

private fun SwiftParserCore.parseDeinitializer(m: PsiBuilder.Marker) {
    advance() // deinit
    parseEffectSpecifiers(E.FUNCTION_EFFECT_SPECIFIERS)
    if (at(T.LBRACE)) parseCodeBlock()
    m.done(E.DEINITIALIZER_DECL)
}

private fun SwiftParserCore.parseSubscript(m: PsiBuilder.Marker) {
    advance() // subscript
    if (atText("<")) parseGenericParameterClause()
    parseParameterClause()
    parseEffectSpecifiers(E.FUNCTION_EFFECT_SPECIFIERS)
    if (at(T.ARROW)) parseReturnClause() else error("Expected '->' for subscript element type")
    if (at(KW.WHERE)) parseGenericWhereClause()
    if (at(T.LBRACE)) parseAccessorBlock()
    m.done(E.SUBSCRIPT_DECL)
}

private fun SwiftParserCore.parseMacroDecl(m: PsiBuilder.Marker) {
    bumpKw() // macro
    expectIdentifier("macro name")
    if (atText("<")) parseGenericParameterClause()
    parseFunctionSignature(allowReturn = true)
    if (at(T.EQ)) parseInitializerClause()
    if (at(KW.WHERE)) parseGenericWhereClause()
    m.done(E.MACRO_DECL)
}

// endregion

// region Variables and accessors

/**
 * Accessor introducers swift-syntax recognizes without experimental features
 * (`AccessorDeclSyntax.AccessorSpecifierOptions`). `read`/`modify` need the experimental
 * coroutine-accessors feature, so they parse as code.
 */
private val ACCESSOR_KEYWORDS = setOf(
    "get", "set", "willSet", "didSet", "_read", "_modify", "unsafeAddress", "unsafeMutableAddress",
    "addressWithOwner", "addressWithNativeOwner", "mutableAddressWithOwner", "mutableAddressWithNativeOwner", "init",
    "borrow", "mutate",
)

private val ACCESSOR_MODIFIERS = setOf("mutating", "nonmutating", "__consuming", "consuming", "borrowing")

private fun SwiftParserCore.parseVariable(m: PsiBuilder.Marker) {
    if (at(KW.TRY)) errorToken("'try' must be placed on the initial expression")
    bumpKw() // let / var / inout / ...
    while (true) {
        val binding = mark()
        parsePattern()
        if (at(T.COLON)) parseTypeAnnotation()
        if (at(T.EQ)) parseInitializerClause()
        if (at(T.LBRACE)) parseAccessorBlock()
        binding.done(E.PATTERN_BINDING)
        if (!consume(T.COMMA)) break
    }
    m.done(E.VARIABLE_DECL)
}

private fun Lookahead.atAccessorStart(): Boolean {
    consumeAttributeList()
    if (atAnyKw(*ACCESSOR_MODIFIERS_ARRAY) && atIdentifierOrKeyword(1)) bump()
    val t = type() ?: return false
    if (t != T.IDENTIFIER && t != KW.INIT) return false
    return text().toString() in ACCESSOR_KEYWORDS
}

private val ACCESSOR_MODIFIERS_ARRAY = ACCESSOR_MODIFIERS.toTypedArray()

internal fun SwiftParserCore.parseAccessorBlock() {
    val m = mark()
    advance() // {
    if (la().atAccessorStart()) {
        while (!eof() && !at(T.RBRACE)) {
            val before = pos
            if (at(T.POUND_IF)) {
                parseIfConfig(IfConfigContext.MEMBERS)
                continue
            }
            if (!la().atAccessorStart()) {
                val err = mark()
                while (!eof() && !at(T.RBRACE) && !la().atAccessorStart()) skipItem()
                err.error("Expected 'get', 'set', 'willSet' or 'didSet'")
                continue
            }
            val a = mark()
            parseAttributeList()
            if (atAnyKw(*ACCESSOR_MODIFIERS_ARRAY) && atIdentifierOrKeyword(1)) {
                val mod = mark()
                bumpKw()
                mod.done(E.DECL_MODIFIER)
            }
            bumpKw() // the accessor keyword
            if (at(T.LPAR) && !atStartOfLine()) {
                val params = mark()
                advance()
                expectIdentifier("parameter name")
                expect(T.RPAR, "')'")
                params.done(E.ACCESSOR_PARAMETERS)
            }
            parseEffectSpecifiers(E.ACCESSOR_EFFECT_SPECIFIERS)
            if (at(T.LBRACE)) parseCodeBlock()
            a.done(E.ACCESSOR_DECL)
            consume(T.SEMICOLON)
            if (pos == before) errorToken("Unexpected token")
        }
    } else {
        parseCodeBlockItems(BlockEnd.BRACE)
    }
    expect(T.RBRACE, "'}' to end accessor block")
    m.done(E.ACCESSOR_BLOCK)
}

// endregion

// region Type aliases, associated types, enum cases

private fun SwiftParserCore.parseTypeAlias(m: PsiBuilder.Marker) {
    advance() // typealias
    if (atIdentifier()) advance() else expectIdentifierRecovering("type alias name")
    if (atText("<")) parseGenericParameterClause()
    if (at(T.EQ)) {
        val init = mark()
        advance()
        parseType()
        init.done(E.TYPE_INITIALIZER_CLAUSE)
    } else {
        error("Expected '=' in type alias declaration")
    }
    if (at(KW.WHERE)) parseGenericWhereClause()
    m.done(E.TYPE_ALIAS_DECL)
}

private fun SwiftParserCore.parseAssociatedType(m: PsiBuilder.Marker) {
    advance() // associatedtype
    if (atIdentifier()) advance() else expectIdentifierRecovering("associated type name")
    if (at(T.COLON)) parseInheritanceClause()
    if (at(T.EQ)) {
        val init = mark()
        advance()
        parseType()
        init.done(E.TYPE_INITIALIZER_CLAUSE)
    }
    if (at(KW.WHERE)) parseGenericWhereClause()
    m.done(E.ASSOCIATED_TYPE_DECL)
}

private fun SwiftParserCore.parseEnumCase(m: PsiBuilder.Marker) {
    advance() // case
    while (true) {
        val el = mark()
        if (atIdentifier()) advance() else expectIdentifierRecovering("identifier in enum 'case' declaration")
        if (at(T.LPAR) && !atStartOfLine()) parseEnumCaseParameters()
        if (at(T.EQ)) parseInitializerClause()
        el.done(E.ENUM_CASE_ELEMENT)
        if (!consume(T.COMMA)) break
    }
    m.done(E.ENUM_CASE_DECL)
}

private fun SwiftParserCore.parseEnumCaseParameters() {
    val m = mark()
    advance() // (
    while (!eof() && !at(T.RPAR)) {
        val before = pos
        val p = mark()
        parseParameterAttributesAndModifiers()
        if (la().startsParameterName(isClosure = false)) {
            bumpName()
            if ((atArgumentLabel() || at(T.WILDCARD)) && !at(T.COLON)) bumpName()
            expect(T.COLON, "':'")
        }
        parseType()
        if (at(T.EQ)) parseInitializerClause()
        p.done(E.ENUM_CASE_PARAMETER)
        if (consume(T.COMMA)) continue
        if (pos == before) errorToken("Unexpected token in enum case parameters")
        else if (!at(T.RPAR)) { error("Expected ','"); break }
    }
    expect(T.RPAR, "')'")
    m.done(E.ENUM_CASE_PARAMETER_CLAUSE)
}

// endregion

// region Operators and precedence groups

private fun SwiftParserCore.parseOperatorDecl(m: PsiBuilder.Marker) {
    advance() // operator
    val t = type()
    when {
        t != null && (TokenCursor.isOperatorLike(t) || t == T.BAD_CHARACTER) && !atStartOfLine() -> bumpOperatorName()
        t == T.IDENTIFIER && !atStartOfLine() -> advance()
        else -> error("Expected operator name in operator declaration")
    }
    if (at(T.COLON)) {
        advance()
        while (true) {
            if (atIdentifierOrKeyword()) advance() else { error("Expected precedence group name"); break }
            if (!consume(T.COMMA)) break
        }
    }
    if (at(T.LBRACE)) {
        val err = mark()
        skipBalanced()
        err.error("Operator should no longer be declared with body")
    }
    m.done(E.OPERATOR_DECL)
}

private fun SwiftParserCore.parsePrecedenceGroup(m: PsiBuilder.Marker) {
    advance() // precedencegroup
    expectIdentifier("precedence group name")
    if (expect(T.LBRACE, "'{'")) {
        while (!eof() && !at(T.RBRACE)) {
            val before = pos
            val a = mark()
            when {
                atKw("higherThan") || atKw("lowerThan") -> {
                    bumpKw()
                    expect(T.COLON, "':'")
                    while (true) {
                        if (atIdentifierOrKeyword()) advance() else { error("Expected precedence group name"); break }
                        if (!consume(T.COMMA)) break
                    }
                }
                atKw("associativity") -> {
                    bumpKw()
                    expect(T.COLON, "':'")
                    if (atAnyKw("left", "right", "none")) bumpKw() else error("Expected 'none', 'left', or 'right'")
                }
                atKw("assignment") -> {
                    bumpKw()
                    expect(T.COLON, "':'")
                    if (at(KW.TRUE) || at(KW.FALSE)) advance() else error("Expected 'true' or 'false'")
                }
                else -> {
                    advance()
                    a.error("Expected 'associativity', 'assignment', 'higherThan', or 'lowerThan'")
                    continue
                }
            }
            a.done(E.PRECEDENCE_GROUP_ATTRIBUTE)
            if (pos == before) errorToken("Unexpected token")
        }
        expect(T.RBRACE, "'}'")
    }
    m.done(E.PRECEDENCE_GROUP_DECL)
}

// endregion
