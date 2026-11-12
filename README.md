# IntelliSwift

Swift language support for IntelliJ-based IDEs, built on a real PSI tree, so that the platform's
PSI-based features work on Swift code: highlighting, structure view, folding, brace matching,
commenting, find usages and more.

The supported language is **Swift 6.0 and later**, including typed throws, noncopyable types and
ownership modifiers, parameter packs, `if`/`switch` expressions, `sending`, `nonisolated(nonsending)`,
value generics, inline arrays, raw identifiers and regex literals.

## How it works

The lexer and parser are written in Kotlin. They are ports of
[swift-syntax](https://github.com/swiftlang/swift-syntax), the parser the Swift compiler uses:
the lexer follows `Lexer/Cursor.swift` and the recursive-descent parser on `PsiBuilder` follows
`SwiftParser`, production by production. The tree's node names match swift-syntax's
(`FUNCTION_DECL`, `SEQUENCE_EXPR`, `IDENTIFIER_TYPE`, ...).

swift-syntax itself is the test oracle. [`tools/swift-syntax-oracle`](tools/swift-syntax-oracle)
dumps swift-syntax's tokens, declaration tree and diagnostics for a set of files, and the
conformance test requires the Kotlin parser to agree on every file of a pinned corpus of
open-source Swift packages. [ADR 0001](docs/adr/0001-parser-architecture.md) explains why the
parser is not swift-syntax called over FFM.

## Requirements

- IntelliJ-based IDE 2026.2 or later (build 262+).
- To build: JDK 25. Gradle is provided by the wrapper.
- For the conformance test: a Swift 6.0+ toolchain.

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`. Install it with
*Settings | Plugins | ⚙ | Install Plugin from Disk...*, or start a sandbox IDE with it:

```bash
./gradlew runIde
```

## Testing

```bash
./gradlew test
```

runs the lexer tests, the parser PSI snapshot tests (`src/test/testData/parser`) and the editor
feature tests. A missing snapshot is generated on the first run; review it before committing.

The swift-syntax conformance test builds the oracle, fetches the corpus listed in
[`scripts/corpus.txt`](scripts/corpus.txt) and compares both parsers:

```bash
scripts/conformance.sh
```

Point it at any other directory of Swift files with `CORPUS_DIR=/path/to/sources`. The report is
written to `build/reports/conformance/report.txt`.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The lexer and parser are derived
from swift-syntax, which is licensed under the Apache License 2.0 with Runtime Library Exception.
