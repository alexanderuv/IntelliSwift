/// Parsed command line of `swift-syntax-oracle`.
public struct OracleOptions: Equatable, Sendable {
    /// Positional file arguments, in order.
    public var files: [String] = []
    /// `--files-from` arguments, in order; `-` means standard input.
    public var listFiles: [String] = []
    /// `--out`; `nil` means standard output.
    public var outputPath: String?
    /// `--jobs`; `nil` means one per active processor.
    public var jobs: Int?
    public var showHelp = false

    public init() {}

    public static let usage = """
        usage: swift-syntax-oracle [--out <path>] [--jobs <n>] [--files-from <listfile>]... [--] [<file>...]

        Parses each Swift file with swift-syntax and writes one JSON object per file (JSON Lines),
        in input order: positional files first, then the paths of each --files-from list.

          --files-from <listfile>  read newline-separated paths from <listfile> ('-' for stdin);
                                   blank lines are ignored; may be repeated
          --out <path>             write output to <path> instead of stdout
          --jobs <n>               number of files parsed concurrently (default: CPU count)
          -h, --help               show this help
        """

    public struct ParseError: Error, Equatable, CustomStringConvertible {
        public let description: String
    }

    public static func parse(_ arguments: some Sequence<String>) throws(ParseError) -> OracleOptions {
        var options = OracleOptions()
        var iterator = arguments.makeIterator()
        var optionsEnded = false

        func value(for option: String) throws(ParseError) -> String {
            guard let value = iterator.next() else {
                throw ParseError(description: "missing value for \(option)")
            }
            return value
        }

        while let argument = iterator.next() {
            if optionsEnded || !argument.hasPrefix("-") || argument == "-" {
                options.files.append(argument)
                continue
            }
            switch argument {
            case "--":
                optionsEnded = true
            case "-h", "--help":
                options.showHelp = true
            case "--files-from":
                options.listFiles.append(try value(for: argument))
            case "--out":
                options.outputPath = try value(for: argument)
            case "--jobs":
                let text = try value(for: argument)
                guard let jobs = Int(text), jobs > 0 else {
                    throw ParseError(description: "--jobs expects a positive integer, got '\(text)'")
                }
                options.jobs = jobs
            default:
                throw ParseError(description: "unknown option \(argument)")
            }
        }
        return options
    }
}

/// Splits the contents of a `--files-from` list into paths: one per line, `\r\n` tolerated, blank
/// lines skipped. Paths are otherwise taken verbatim (no trimming of spaces).
public func pathsFromList(_ contents: [UInt8]) -> [String] {
    var paths: [String] = []
    for line in contents.split(separator: UInt8(ascii: "\n"), omittingEmptySubsequences: true) {
        var line = line
        if line.last == UInt8(ascii: "\r") { line = line.dropLast() }
        if line.isEmpty { continue }
        paths.append(String(decoding: line, as: UTF8.self))
    }
    return paths
}
