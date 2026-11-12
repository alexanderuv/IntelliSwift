import SwiftSyntax

/// One declaration in the skeleton: every `DeclSyntax` node in the source-accurate tree, nested
/// under its nearest declaration ancestor.
public struct DeclNode: Equatable, Sendable {
    /// Syntax node type name without the `Syntax` suffix, e.g. `FunctionDecl`, `IfConfigDecl`.
    public var kind: String
    /// See `declName(_:)` for which declarations carry a name.
    public var name: String?
    public var children: [DeclNode]

    public init(kind: String, name: String?, children: [DeclNode] = []) {
        self.kind = kind
        self.name = name
        self.children = children
    }
}

/// Builds the declaration forest of `tree`.
///
/// Uses the source-accurate view, so nodes consisting solely of missing tokens (synthesized by the
/// parser during recovery) are skipped, while declarations found inside unexpected-node runs are
/// kept, because their text is part of the file.
public func declSkeleton(of tree: some SyntaxProtocol) -> [DeclNode] {
    let builder = DeclSkeletonBuilder()
    builder.walk(tree)
    return builder.roots
}

private final class DeclSkeletonBuilder: SyntaxAnyVisitor {
    /// Open declarations from the outermost to the innermost; index 0 is a synthetic root.
    private var stack: [DeclNode] = [DeclNode(kind: "", name: nil)]
    private var kindNames: [SyntaxKind: String] = [:]

    var roots: [DeclNode] { stack[0].children }

    init() {
        super.init(viewMode: .sourceAccurate)
    }

    override func visitAny(_ node: Syntax) -> SyntaxVisitorContinueKind {
        if let decl = node.as(DeclSyntax.self) {
            stack.append(DeclNode(kind: kindName(of: decl), name: declName(decl)))
        }
        return .visitChildren
    }

    override func visitAnyPost(_ node: Syntax) {
        guard node.is(DeclSyntax.self) else { return }
        let finished = stack.removeLast()
        stack[stack.count - 1].children.append(finished)
    }

    override func visit(_ token: TokenSyntax) -> SyntaxVisitorContinueKind {
        .skipChildren
    }

    private func kindName(of decl: DeclSyntax) -> String {
        if let cached = kindNames[decl.kind] { return cached }
        var name = String(describing: decl.syntaxNodeType)
        if name.hasSuffix("Syntax") { name.removeLast("Syntax".count) }
        kindNames[decl.kind] = name
        return name
    }
}

/// Primary identifier of a declaration as the oracle reports it:
/// - `ExtensionDecl`: the extended type's trimmed source text (`Array<Int>`, `Foo.Bar`).
/// - `VariableDecl`: binding identifiers joined by `,`; `nil` unless every pattern is a plain
///   identifier (tuple and wildcard patterns yield `nil`).
/// - `ImportDecl`: the path components joined by `.`.
/// - `AccessorDecl`: the specifier keyword (`get`, `set`, `willSet`, `didSet`, `init`, ...).
/// - `EnumCaseDecl`: element names joined by `,`.
/// - `MacroExpansionDecl`: the macro name without `#`.
/// - Any other declaration with a `name` token (struct, class, enum, protocol, actor, func,
///   typealias, associatedtype, operator, precedencegroup, macro): that token's text.
/// - Everything else (init, deinit, subscript, `#if`, missing decls, ...): `nil`.
///
/// Token text is used verbatim, so backtick-escaped names keep their backticks. A component made of
/// a missing token makes the whole name `nil`.
public func declName(_ decl: DeclSyntax) -> String? {
    if let ext = decl.as(ExtensionDeclSyntax.self) {
        return ext.extendedType.trimmedDescription
    }
    if let variable = decl.as(VariableDeclSyntax.self) {
        var names: [String] = []
        for binding in variable.bindings {
            guard let identifier = binding.pattern.as(IdentifierPatternSyntax.self),
                  let text = presentText(identifier.identifier) else { return nil }
            names.append(text)
        }
        return names.isEmpty ? nil : names.joined(separator: ",")
    }
    if let importDecl = decl.as(ImportDeclSyntax.self) {
        var components: [String] = []
        for component in importDecl.path {
            guard let text = presentText(component.name) else { return nil }
            components.append(text)
        }
        return components.isEmpty ? nil : components.joined(separator: ".")
    }
    if let accessor = decl.as(AccessorDeclSyntax.self) {
        return presentText(accessor.accessorSpecifier)
    }
    if let enumCase = decl.as(EnumCaseDeclSyntax.self) {
        var names: [String] = []
        for element in enumCase.elements {
            guard let text = presentText(element.name) else { return nil }
            names.append(text)
        }
        return names.isEmpty ? nil : names.joined(separator: ",")
    }
    if let macroExpansion = decl.as(MacroExpansionDeclSyntax.self) {
        return presentText(macroExpansion.macroName)
    }
    if let named = decl.asProtocol(NamedDeclSyntax.self) {
        return presentText(named.name)
    }
    return nil
}

private func presentText(_ token: TokenSyntax) -> String? {
    token.presence == .present ? token.text : nil
}
