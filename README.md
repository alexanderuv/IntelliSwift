# IntelliSwift

Swift language support for IntelliJ-based IDEs, built on a real PSI tree, so that the platform's
PSI-based features work on Swift code: highlighting, structure view, folding, brace matching,
commenting, find usages and more.

The supported language is **Swift 6.0 and later**, including typed throws, noncopyable types and
ownership modifiers, parameter packs, `if`/`switch` expressions, `sending`, `nonisolated(nonsending)`,
value generics, inline arrays, raw identifiers and regex literals.

## Swift packages

SwiftPM packages are integrated the way Gradle projects are ([ADR 0002](docs/adr/0002-swiftpm-integration.md)):

- **Import.** Open a directory with a `Package.swift` (or *File | Open* the manifest) and load it.
  Every package and target becomes a module, with source and test roots. Local `path:`
  dependencies stay editable modules, and checked-out dependencies become libraries with their
  sources. The project reloads when `Package.swift` or `Package.resolved` changes.
- **SwiftPM tool window.** Build, test, clean, resolve, update and run tasks per package, and a
  build task per target.
- **Build.** *Build Project*, *Build Module*, *Rebuild* and *Compile* run `swift build` (debug or
  release), with navigable compiler, linker and manifest errors in the Build tool window.

The toolchain is detected from `PATH`, `xcrun`, swiftly and the standard install locations, and
can be set in *Settings | Languages & Frameworks | Swift*. Debugging is not supported yet.

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
- For Swift packages: a Swift 6.0+ toolchain.
- To build: JDK 25. Gradle is provided by the wrapper.
- For the conformance test and the SwiftPM integration tests: a Swift 6.0+ toolchain. Without
  one, the tests that run Swift are skipped.

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

runs the lexer tests, the parser PSI snapshot tests (`src/test/testData/parser`), the editor
feature tests and the SwiftPM tests. A missing snapshot is generated on the first run; review it
before committing. The SwiftPM tests import and build the fixture packages in `src/test/testData/swiftpm`
with the local toolchain.

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
