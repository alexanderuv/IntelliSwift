#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#endif
import SwiftDiagnostics
import SwiftParser
import SwiftParserDiagnostics
import SwiftSyntax

/// Produces the oracle's per-file JSON object (one line, without the trailing newline).
public enum FileReport {
    /// Reads `path` and renders its report, or an `{"path", "error"}` object if it can't be read.
    public static func render(path: String) -> [UInt8] {
        switch readFile(atPath: path) {
        case .success(let source):
            return render(path: path, source: source)
        case .failure(let error):
            var json = JSONWriter(reservingCapacity: 128)
            json.raw("{\"path\":")
            json.string(path)
            json.raw(",\"error\":")
            json.string(error.message)
            json.raw("}")
            return json.bytes
        }
    }

    /// Parses `source` once and renders tokens, declaration skeleton and diagnostics for it.
    public static func render(path: String, source: [UInt8]) -> [UInt8] {
        let tree = source.withUnsafeBufferPointer { Parser.parse(source: $0) }
        var json = JSONWriter(reservingCapacity: source.count * 4 + 256)

        json.raw("{\"path\":")
        json.string(path)

        json.raw(",\"tokens\":[")
        var firstToken = true
        for token in tree.tokens(viewMode: .sourceAccurate) {
            guard token.presence == .present, token.tokenKind != .endOfFile else { continue }
            if !firstToken { json.raw(byte: UInt8(ascii: ",")) }
            firstToken = false
            json.raw(byte: UInt8(ascii: "["))
            json.string(tokenKindName(token.tokenKind))
            json.raw(byte: UInt8(ascii: ","))
            json.string(token.text)
            json.raw(byte: UInt8(ascii: "]"))
        }

        json.raw("],\"decls\":")
        writeDecls(declSkeleton(of: tree), into: &json)

        json.raw(",\"diagnostics\":[")
        let diagnostics = ParseDiagnosticsGenerator.diagnostics(for: tree)
        if !diagnostics.isEmpty {
            let converter = SourceLocationConverter(fileName: path, tree: tree)
            for (index, diagnostic) in diagnostics.enumerated() {
                if index > 0 { json.raw(byte: UInt8(ascii: ",")) }
                let location = diagnostic.location(converter: converter)
                json.raw("{\"line\":")
                json.integer(location.line)
                json.raw(",\"column\":")
                json.integer(location.column)
                json.raw(",\"message\":")
                json.string(diagnostic.message)
                json.raw(",\"severity\":")
                json.string(severityName(diagnostic.diagMessage.severity))
                json.raw("}")
            }
        }
        json.raw("]}")
        return json.bytes
    }

    private static func writeDecls(_ decls: [DeclNode], into json: inout JSONWriter) {
        json.raw(byte: UInt8(ascii: "["))
        for (index, decl) in decls.enumerated() {
            if index > 0 { json.raw(byte: UInt8(ascii: ",")) }
            json.raw("{\"kind\":")
            json.string(decl.kind)
            json.raw(",\"name\":")
            json.string(orNull: decl.name)
            json.raw(",\"children\":")
            writeDecls(decl.children, into: &json)
            json.raw(byte: UInt8(ascii: "}"))
        }
        json.raw(byte: UInt8(ascii: "]"))
    }
}

func severityName(_ severity: DiagnosticSeverity) -> String {
    switch severity {
    case .error: return "error"
    case .warning: return "warning"
    case .note: return "note"
    case .remark: return "remark"
    @unknown default: return String(describing: severity)
    }
}

public struct ReadError: Error, Sendable {
    public let message: String
}

/// Reads a whole file with POSIX calls, so the error text is the plain `strerror` message
/// (e.g. "No such file or directory", "Is a directory") on every platform.
public func readFile(atPath path: String) -> Result<[UInt8], ReadError> {
    let fd = open(path, O_RDONLY)
    guard fd >= 0 else { return .failure(ReadError(message: errnoMessage())) }
    defer { close(fd) }

    var info = stat()
    guard fstat(fd, &info) == 0 else { return .failure(ReadError(message: errnoMessage())) }

    var bytes: [UInt8] = []
    bytes.reserveCapacity(Int(info.st_size) + 1)
    let chunkSize = 1 << 16
    var chunk = [UInt8](repeating: 0, count: chunkSize)
    while true {
        let count = chunk.withUnsafeMutableBytes { read(fd, $0.baseAddress, chunkSize) }
        if count < 0 {
            if errno == EINTR { continue }
            return .failure(ReadError(message: errnoMessage()))
        }
        if count == 0 { break }
        bytes.append(contentsOf: chunk[0..<count])
    }
    return .success(bytes)
}

private func errnoMessage() -> String {
    String(cString: strerror(errno))
}
