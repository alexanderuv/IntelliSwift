# swift-syntax-oracle

Reference oracle for IntelliSwift's hand-written Kotlin lexer/parser. It parses Swift files with
[swift-syntax](https://github.com/swiftlang/swift-syntax) (the parser the Swift compiler uses) and
dumps, per file, the token stream, a declaration skeleton and the parse diagnostics as JSON Lines.
The Kotlin test harness runs the same files through the Kotlin parser and compares.

## Build

```sh
swift build -c release --package-path tools/swift-syntax-oracle
# binary: tools/swift-syntax-oracle/.build/release/swift-syntax-oracle
swift test --package-path tools/swift-syntax-oracle
```

Requires a Swift 6.0+ toolchain. swift-syntax is accepted from `600.0.0` up to (excluding)
`700.0.0`; `Package.resolved` pins the version used for development (604.0.0). Because the output
reflects whatever swift-syntax version gets resolved, compare against the pinned version.

## CLI

```
swift-syntax-oracle [--out <path>] [--jobs <n>] [--files-from <listfile>]... [--] [<file>...]
```

| Option | Meaning |
| --- | --- |
| `<file>...` | Files to process. |
| `--files-from <listfile>` | Newline-separated paths (`\r\n` accepted, blank lines skipped, no other trimming). `-` reads the list from stdin. Repeatable. |
| `--out <path>` | Write output to `<path>` instead of stdout. |
| `--jobs <n>` | Files parsed concurrently (default: online CPU count). Output order and content do not depend on it. |
| `-h`, `--help` | Usage. |

Positional files come first, then the entries of each `--files-from` list, in order. Each file is
read and parsed exactly once; files are parsed in parallel and written in input order as soon as
all earlier ones are done.

Exit status: `0` when every input produced a line (including per-file read errors), `1` on an
output error, `2` on a usage error or an unreadable `--files-from` list.

## Output

JSON Lines: exactly one JSON object per input file, one per line (`\n`-terminated), in input
order, encoded as UTF-8. Keys always appear in the order shown.

```json
{
  "path": "Sources/Foo.swift",
  "tokens": [["keyword:let", "let"], ["identifier", "x"], ["equal", "="], ["integerLiteral", "1"]],
  "decls": [{"kind": "VariableDecl", "name": "x", "children": []}],
  "diagnostics": [{"line": 1, "column": 5, "message": "...", "severity": "error"}]
}
```

A file that cannot be read yields `{"path": "<path>", "error": "<strerror message>"}` (e.g.
`"No such file or directory"`, `"Is a directory"`) and processing continues.

### `path`

The path exactly as given on the command line or in the list file.

### `tokens`

Every token of `SourceFileSyntax.tokens(viewMode: .sourceAccurate)`, in source order, as a
`[kind, text]` pair, excluding missing tokens (inserted by error recovery) and the final
`endOfFile` token. `text` is `token.text`: the token without trivia (whitespace, comments,
`\`-newline line continuations, multi-line string indentation all live in trivia).

`kind` is the token kind after parsing, so contextual keywords are reported as keywords only
where the parser treated them as such (`get` in an accessor block is `keyword:get`, `get` used as a
variable name is `identifier`):

- `keyword:<text>` for keywords, where `<text>` is the keyword's spelling, e.g. `keyword:func`,
  `keyword:Self`, `keyword:willSet`, `keyword:some`, `keyword:inout`.
- Otherwise the `TokenKind` case name, one of:
  `arrow`, `atSign`, `backslash`, `backtick`, `binaryOperator`, `colon`, `colonColon`, `comma`,
  `dollarIdentifier`, `ellipsis`, `equal`, `exclamationMark`, `floatLiteral`, `identifier`,
  `infixQuestionMark`, `integerLiteral`, `leftAngle`, `leftBrace`, `leftParen`, `leftSquare`,
  `multilineStringQuote`, `period`, `postfixOperator`, `postfixQuestionMark`, `pound`,
  `poundAvailable`, `poundElse`, `poundElseif`, `poundEndif`, `poundIf`, `poundSourceLocation`,
  `poundUnavailable`, `prefixAmpersand`, `prefixOperator`, `rawStringPoundDelimiter`,
  `regexLiteralPattern`, `regexPoundDelimiter`, `regexSlash`, `rightAngle`, `rightBrace`,
  `rightParen`, `rightSquare`, `semicolon`, `shebang`, `singleQuote`, `stringQuote`,
  `stringSegment`, `unknown`, `wildcard`.
  (`endOfFile` exists in the mapping but is never emitted; `colonColon` requires swift-syntax 603+.
  A kind added by a future swift-syntax release is emitted under its case name.)

Notable swift-syntax behaviours the Kotlin side has to reproduce:

- **String literals** are split into `stringQuote` / `multilineStringQuote` / `singleQuote`,
  `rawStringPoundDelimiter` (the `#`s, also inside `\#(`), `stringSegment`, and for
  interpolation `backslash` `leftParen` ... `rightParen`. `""` contains one empty `stringSegment`.
  A segment also ends after an escaped newline `\n`.
- **Multi-line strings** have one `stringSegment` per line; the line's indentation is trivia, so
  segments contain only text beyond the closing delimiter's indentation, and include the
  trailing `\n` except on the last line. The newline after the opening `"""` is trivia.
- **Regex literals**: `regexPoundDelimiter`? `regexSlash` `regexLiteralPattern` `regexSlash`
  `regexPoundDelimiter`?.
- **`>>` in generic argument lists** is split into separate `rightAngle` tokens; in expressions it
  stays a single `binaryOperator`.
- Operator names in `operator` declarations and operator functions are `binaryOperator` even for
  `prefix`/`postfix` operators; in expressions the fixity is reflected (`prefixOperator`, ...).
- Invalid UTF-8 in the source shows up as U+FFFD in `text`.

### `decls`

The declaration skeleton: every node of the source-accurate tree that is a `DeclSyntax` becomes

```json
{"kind": "<SyntaxNodeType without Syntax suffix>", "name": "<string>" | null, "children": [...]}
```

e.g. `FunctionDecl`, `StructDecl`, `IfConfigDecl`, `AccessorDecl`, `MissingDecl`,
`PoundSourceLocation`, `MacroExpansionDecl` (the kind is the swift-syntax node type name, so any
decl node type swift-syntax has, including `UnexpectedCodeDecl` in 604, can appear).
`children` are the nearest descendant declarations, wherever they are nested (member blocks,
function and closure bodies, `#if` clauses, accessor blocks, and also inside unexpected-node runs).
Nodes made only of missing tokens are not part of the source-accurate tree and do not appear.

`name`:

| Decl | `name` |
| --- | --- |
| `ExtensionDecl` | extended type, trimmed source text (`Array<Int>`, `Swift.Dictionary.Keys`) |
| `VariableDecl` | binding identifiers joined by `,` (`var a, b: Int` → `"a,b"`); `null` if any pattern is not a plain identifier (tuple, `_`) |
| `ImportDecl` | path joined by `.` (`import struct Swift.Array` → `"Swift.Array"`) |
| `AccessorDecl` | accessor specifier (`get`, `set`, `willSet`, `didSet`, `_modify`, `init`, ...) |
| `EnumCaseDecl` | element names joined by `,` |
| `MacroExpansionDecl` | macro name without `#` |
| other decls with a `name` token (`struct`, `class`, `enum`, `protocol`, `actor`, `func`, `typealias`, `associatedtype`, `operator`, `precedencegroup`, `macro`) | that token's text |
| everything else (`init`, `deinit`, `subscript`, `#if`, `MissingDecl`, ...) | `null` |

Names are token text verbatim, so backtick-escaped names keep their backticks
(`` var `class` `` → ``"`class`"``). If any component token is missing, `name` is `null`.

Freestanding macros in statement/top-level position (`#warning("x")` at file scope or in a
function body) are parsed as expressions by swift-syntax and therefore produce no decl; inside
type member blocks they are `MacroExpansionDecl`.

### `diagnostics`

`ParseDiagnosticsGenerator.diagnostics(for:)` in emission order:
`{"line", "column", "message", "severity"}`. `line` and `column` are 1-based, from
`SourceLocationConverter`; `column` counts UTF-8 bytes. `severity` is `error`, `warning`, `note`
or `remark`. Notes attached to a diagnostic and fix-its are not included.

## Layout

- `Sources/OracleCore` — token-kind mapping, decl skeleton, JSON writer, report rendering,
  ordered parallel runner, CLI option parsing.
- `Sources/swift-syntax-oracle` — the executable (argument handling, buffered output).
- `Tests/OracleCoreTests` — unit tests; `Tests/Fixtures` — hand-written tricky inputs
  (`Errors.swift` and `ControlChars.swift` intentionally produce diagnostics).
