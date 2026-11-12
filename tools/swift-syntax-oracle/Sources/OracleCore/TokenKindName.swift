import SwiftSyntax

/// Maps a post-parse `TokenKind` to the stable string the oracle emits.
///
/// Keywords become `"keyword:<text>"` (e.g. `"keyword:func"`, `"keyword:get"`); every other kind
/// becomes its `TokenKind` case name. The switch is spelled out explicitly so that a rename inside
/// swift-syntax cannot silently change the oracle's output; only kinds this file does not know about
/// (added by a swift-syntax release newer than the code) go through the reflective fallback.
public func tokenKindName(_ kind: TokenKind) -> String {
    switch kind {
    case .arrow: return "arrow"
    case .atSign: return "atSign"
    case .backslash: return "backslash"
    case .backtick: return "backtick"
    case .binaryOperator: return "binaryOperator"
    case .colon: return "colon"
    #if canImport(SwiftSyntax603)
    // Introduced in swift-syntax 603 (module selectors, `Module::name`).
    case .colonColon: return "colonColon"
    #endif
    case .comma: return "comma"
    case .dollarIdentifier: return "dollarIdentifier"
    case .ellipsis: return "ellipsis"
    case .endOfFile: return "endOfFile"
    case .equal: return "equal"
    case .exclamationMark: return "exclamationMark"
    case .floatLiteral: return "floatLiteral"
    case .identifier: return "identifier"
    case .infixQuestionMark: return "infixQuestionMark"
    case .integerLiteral: return "integerLiteral"
    case .keyword(let keyword): return keywordKindNames[Int(keyword.rawValue)]
    case .leftAngle: return "leftAngle"
    case .leftBrace: return "leftBrace"
    case .leftParen: return "leftParen"
    case .leftSquare: return "leftSquare"
    case .multilineStringQuote: return "multilineStringQuote"
    case .period: return "period"
    case .postfixOperator: return "postfixOperator"
    case .postfixQuestionMark: return "postfixQuestionMark"
    case .pound: return "pound"
    case .poundAvailable: return "poundAvailable"
    case .poundElse: return "poundElse"
    case .poundElseif: return "poundElseif"
    case .poundEndif: return "poundEndif"
    case .poundIf: return "poundIf"
    case .poundSourceLocation: return "poundSourceLocation"
    case .poundUnavailable: return "poundUnavailable"
    case .prefixAmpersand: return "prefixAmpersand"
    case .prefixOperator: return "prefixOperator"
    case .rawStringPoundDelimiter: return "rawStringPoundDelimiter"
    case .regexLiteralPattern: return "regexLiteralPattern"
    case .regexPoundDelimiter: return "regexPoundDelimiter"
    case .regexSlash: return "regexSlash"
    case .rightAngle: return "rightAngle"
    case .rightBrace: return "rightBrace"
    case .rightParen: return "rightParen"
    case .rightSquare: return "rightSquare"
    case .semicolon: return "semicolon"
    case .shebang: return "shebang"
    case .singleQuote: return "singleQuote"
    case .stringQuote: return "stringQuote"
    case .stringSegment: return "stringSegment"
    case .unknown: return "unknown"
    case .wildcard: return "wildcard"
    @unknown default: return reflectedCaseName(kind)
    }
}

/// `"keyword:<text>"` for every `Keyword`, indexed by raw value.
///
/// The keyword's source text (`Keyword.defaultText`) is SPI, so it is obtained through the public
/// `TokenSyntax.keyword(_:)` factory instead; that allocates, hence the one-time table.
private let keywordKindNames: [String] = (0...Int(UInt8.max)).map { rawValue in
    guard let keyword = Keyword(rawValue: UInt8(rawValue)) else { return "keyword:?" }
    return "keyword:" + TokenSyntax.keyword(keyword).text
}

/// Case name of an enum value, for kinds added after this file was written: the payload label for
/// cases with associated values, otherwise the default (reflective) description.
private func reflectedCaseName(_ kind: TokenKind) -> String {
    if let label = Mirror(reflecting: kind).children.first?.label {
        return label
    }
    return String(describing: kind)
}
