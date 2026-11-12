package io.github.alexanderuv.intelliswift.lexer

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * The keyword table from swift-syntax (`CodeGeneration/Sources/SyntaxSupport/KeywordSpec.swift`).
 * Reserved keywords (`isLexerClassified: true` there) are produced by the lexer; all others are
 * contextual and only become keyword tokens when the parser remaps an identifier.
 */
object SwiftKeywords {
    private val RESERVED = listOf(
        "Any", "as", "associatedtype", "break", "case", "catch", "class", "continue", "default", "defer",
        "deinit", "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "for", "func",
        "guard", "if", "import", "in", "init", "inout", "internal", "is", "let", "nil", "operator",
        "precedencegroup", "private", "protocol", "public", "repeat", "rethrows", "return", "self", "Self",
        "static", "struct", "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias",
        "var", "where", "while",
    )

    private val CONTEXTUAL = listOf(
        "__consuming", "__owned", "__setter_access", "__shared", "_backDeploy", "_borrow", "_borrowing",
        "_BridgeObject", "_Class", "_compilerInitialized", "_const", "_consuming", "_documentation",
        "_dynamicReplacement", "_effects", "_forward", "_implements", "_linear", "_local", "_modify", "_move",
        "_mutating", "_NativeClass", "_NativeRefCountedObject", "_noMetadata", "_opaqueReturnTypeOf",
        "_originallyDefinedIn", "_PackageDescription", "_read", "_RefCountedObject", "specialized", "_specialize",
        "_spi_available", "_Trivial", "_TrivialAtMost", "_TrivialStride", "_underlyingVersion", "_UnknownLayout",
        "_version", "abi", "accesses", "actor", "addressWithNativeOwner", "addressWithOwner", "any", "assignment",
        "associativity", "async", "attached", "autoclosure", "availability", "available", "await",
        "backDeployed", "before", "block", "borrow", "borrowing", "canImport", "compiler", "consume", "copy",
        "consuming", "convenience", "convention", "dependsOn", "deprecated", "derivative", "didSet",
        "differentiable", "distributed", "dynamic", "each", "escaping", "exported", "file", "final", "discard",
        "forward", "freestanding", "get", "higherThan", "indirect", "infix", "initializes", "introduced",
        "isolated", "kind", "lazy", "left", "line", "linear", "lowerThan", "macro", "message", "metadata",
        "modify", "module", "mutate", "mutableAddressWithNativeOwner", "mutableAddressWithOwner", "mutating",
        "noasync", "noDerivative", "noescape", "none", "nonisolated", "nonmutating", "nonsending", "objc",
        "obsoleted", "of", "open", "optional", "override", "package", "postfix", "prefix", "Protocol", "read",
        "reasync", "renamed", "required", "retroactive", "reverse", "right", "safe", "scoped", "Sendable", "set",
        "some", "spi", "spiModule", "swift", "target", "then", "transpose", "Type", "unavailable", "unchecked",
        "preconcurrency", "unowned", "unsafe", "unsafeAddress", "unsafeMutableAddress", "using", "visibility",
        "weak", "willSet", "wrt", "yield", "yielding", "sending",
    )

    private val byText: Map<String, SwiftKeywordTokenType> = buildMap {
        for (k in RESERVED) put(k, SwiftKeywordTokenType(k, isReserved = true))
        for (k in CONTEXTUAL) put(k, SwiftKeywordTokenType(k, isReserved = false))
    }

    /** The keyword token type for [text], reserved or contextual, or null. */
    fun keyword(text: CharSequence): SwiftKeywordTokenType? = byText[text.toString()]

    /** The keyword token type for [text]; throws if [text] is not a keyword. */
    operator fun get(text: String): SwiftKeywordTokenType =
        byText[text] ?: throw IllegalArgumentException("Not a Swift keyword: $text")

    fun reserved(text: CharSequence): SwiftKeywordTokenType? = byText[text.toString()]?.takeIf { it.isReserved }

    @JvmField val RESERVED_KEYWORDS: TokenSet = TokenSet.create(*RESERVED.map { byText.getValue(it) as IElementType }.toTypedArray())
    @JvmField val CONTEXTUAL_KEYWORDS: TokenSet = TokenSet.create(*CONTEXTUAL.map { byText.getValue(it) as IElementType }.toTypedArray())
    @JvmField val ALL_KEYWORDS: TokenSet = TokenSet.orSet(RESERVED_KEYWORDS, CONTEXTUAL_KEYWORDS)
}

/** Short aliases for keyword token types, used throughout the parser. */
object KW {
    val ANY_TYPE = SwiftKeywords["Any"]
    val AS = SwiftKeywords["as"]
    val ASSOCIATEDTYPE = SwiftKeywords["associatedtype"]
    val BREAK = SwiftKeywords["break"]
    val CASE = SwiftKeywords["case"]
    val CATCH = SwiftKeywords["catch"]
    val CLASS = SwiftKeywords["class"]
    val CONTINUE = SwiftKeywords["continue"]
    val DEFAULT = SwiftKeywords["default"]
    val DEFER = SwiftKeywords["defer"]
    val DEINIT = SwiftKeywords["deinit"]
    val DO = SwiftKeywords["do"]
    val ELSE = SwiftKeywords["else"]
    val ENUM = SwiftKeywords["enum"]
    val EXTENSION = SwiftKeywords["extension"]
    val FALLTHROUGH = SwiftKeywords["fallthrough"]
    val FALSE = SwiftKeywords["false"]
    val FILEPRIVATE = SwiftKeywords["fileprivate"]
    val FOR = SwiftKeywords["for"]
    val FUNC = SwiftKeywords["func"]
    val GUARD = SwiftKeywords["guard"]
    val IF = SwiftKeywords["if"]
    val IMPORT = SwiftKeywords["import"]
    val IN = SwiftKeywords["in"]
    val INIT = SwiftKeywords["init"]
    val INOUT = SwiftKeywords["inout"]
    val INTERNAL = SwiftKeywords["internal"]
    val IS = SwiftKeywords["is"]
    val LET = SwiftKeywords["let"]
    val NIL = SwiftKeywords["nil"]
    val OPERATOR = SwiftKeywords["operator"]
    val PRECEDENCEGROUP = SwiftKeywords["precedencegroup"]
    val PRIVATE = SwiftKeywords["private"]
    val PROTOCOL = SwiftKeywords["protocol"]
    val PUBLIC = SwiftKeywords["public"]
    val REPEAT = SwiftKeywords["repeat"]
    val RETHROWS = SwiftKeywords["rethrows"]
    val RETURN = SwiftKeywords["return"]
    val SELF = SwiftKeywords["self"]
    val SELF_TYPE = SwiftKeywords["Self"]
    val STATIC = SwiftKeywords["static"]
    val STRUCT = SwiftKeywords["struct"]
    val SUBSCRIPT = SwiftKeywords["subscript"]
    val SUPER = SwiftKeywords["super"]
    val SWITCH = SwiftKeywords["switch"]
    val THROW = SwiftKeywords["throw"]
    val THROWS = SwiftKeywords["throws"]
    val TRUE = SwiftKeywords["true"]
    val TRY = SwiftKeywords["try"]
    val TYPEALIAS = SwiftKeywords["typealias"]
    val VAR = SwiftKeywords["var"]
    val WHERE = SwiftKeywords["where"]
    val WHILE = SwiftKeywords["while"]
}
