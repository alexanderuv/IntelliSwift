# ADR 0001: A native Kotlin lexer and parser, validated against swift-syntax

- Status: accepted
- Date: 2026-11-11

## Context

IntelliSwift adds Swift support to IntelliJ-based IDEs. The goal is that Swift
files get a real PSI tree, so that every platform feature built on PSI (highlighting,
structure view, folding, navigation, find usages, refactoring, inspections, formatting)
can work on Swift code.

The supported language is **Swift 6.0 and later**.

We considered three ways to get from source text to PSI:

1. **Call swift-syntax from the IDE.** IntelliJ 2026.x runs on JetBrains Runtime 25,
   so the Foreign Function & Memory API is final and in-process, and
   [swift-java](https://github.com/swiftlang/swift-java)'s `jextract --mode=ffm` can
   generate Java bindings for Swift code. A `PsiParser` could ask swift-syntax for a
   tree and replay its node ranges into `PsiBuilder` markers.
2. **Generate a parser with Grammar-Kit.** The usual route for IntelliJ plugins.
3. **Hand-write a lexer and a recursive-descent parser in Kotlin on `PsiBuilder`**,
   using swift-syntax's own `SwiftParser` as the reference for every grammar decision.

## Decision

We take option 3. The Kotlin lexer is a port of swift-syntax's lexer (`Lexer/Cursor.swift`,
`RegexLiteralLexer.swift`), and the parser follows the structure and lookahead rules of
swift-syntax's `SwiftParser`.

We still reuse swift-syntax, where it helps without tying the IDE to native code:
it is the **conformance oracle**. `tools/swift-syntax-oracle` is a small SwiftPM
CLI that dumps tokens, the declaration tree and diagnostics for a set of files.
The conformance test runs both parsers over a corpus of real Swift code and fails
on any difference.

## Why not call swift-syntax over FFM

- **IDE stability.** A crash in native code (a Swift runtime trap, a precondition in the
  parser, a bad memory access across the FFM boundary) takes down the whole IDE process.
  A Kotlin exception in a parser only fails that file's highlighting.
- **Distribution.** The plugin would have to bundle a native library for every OS and
  architecture IntelliJ runs on (macOS arm64/x64, Linux x64/arm64, Windows x64/arm64).
  On Linux and Windows that also means bundling or statically linking the Swift runtime.
  A Kotlin parser runs wherever the IDE runs.
- **The lexer has to be ours anyway.** IntelliJ's highlighting lexer restarts in the
  middle of a file after every edit and needs a small integer state at restart points.
  swift-syntax's lexer cannot start mid-file, so we would need a Kotlin lexer that agrees
  with swift-syntax token for token. Once that exists, the gap to a full parser is the
  grammar, which swift-syntax spells out for us.
- **Incremental reparsing.** `PsiBuilder` and lazy-reparseable elements (such as code
  blocks) let IntelliJ reparse only the edited block. Replaying an external tree into
  `PsiBuilder` means reparsing the whole file through FFM on every keystroke.
- **Token mapping.** swift-syntax stores whitespace and comments as trivia on tokens and
  splits or merges tokens during parsing (`>>` in `Array<Array<Int>>`). PSI wants
  whitespace and comments as leaves. Replay would need a fragile alignment layer.

FFM stays a good fit for things that are naturally whole-file or whole-module
and not on the typing path, such as running swift-format. That is a separate decision.

## Why not Grammar-Kit

Swift's grammar depends on context in ways a BNF generator handles badly: newline-sensitive
postfix expressions, contextual keywords, operator classification from surrounding
whitespace, generic-argument versus less-than disambiguation, trailing closures, and
regex-literal versus division. swift-syntax handles these with hand-written lookahead,
which ports to a hand-written parser one-for-one. JetBrains has also scaled back its
Gradle Grammar-Kit tooling.

## Consequences

- We own a large parser and have to keep it in step with new Swift releases. The
  conformance test is what makes that tractable: a new Swift version is supported once
  the oracle, built on that version's swift-syntax, agrees with us on the corpus.
- The token stream deliberately differs from swift-syntax in a few documented ways. For
  example, swift-syntax's parser splits a leading `<` or `>` off an operator token where a
  generic clause opens or closes (`Vector<-1>`, `Array<Array<Int>>`), but `PsiBuilder` cannot
  split tokens. So our lexer emits such operators in pieces and flags each continuation piece,
  and the parser merges them back with `PsiBuilder.Marker.collapse` wherever it does not take
  the first piece as an angle bracket. The conformance test compares the post-parse leaves, so
  these differences cancel out.
- Error recovery follows swift-syntax too: `expect` ports `canRecoverTo` and `TokenPrecedence`,
  so a missing token is looked for only past tokens that bind less strongly than it does.
- Semantic features (type inference, cross-module resolution, code completion beyond the
  file) are out of scope for this ADR. Candidates are our own resolver on PSI, SourceKit-LSP
  through the platform LSP API, or both.
