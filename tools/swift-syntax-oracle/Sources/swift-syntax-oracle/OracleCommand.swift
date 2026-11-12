#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#endif
import OracleCore

/// Buffered writer over a raw file descriptor; output is flushed in large chunks.
final class OutputSink {
    private let fd: Int32
    private let ownsDescriptor: Bool
    private var buffer: [UInt8] = []
    private let flushThreshold = 1 << 20

    struct WriteError: Error, CustomStringConvertible {
        let description: String
    }

    init(path: String?) throws(WriteError) {
        if let path {
            let fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
            guard fd >= 0 else {
                throw WriteError(description: "cannot open \(path): \(String(cString: strerror(errno)))")
            }
            self.fd = fd
            ownsDescriptor = true
        } else {
            fd = STDOUT_FILENO
            ownsDescriptor = false
        }
        buffer.reserveCapacity(flushThreshold * 2)
    }

    func writeLine(_ line: [UInt8]) throws(WriteError) {
        buffer.append(contentsOf: line)
        buffer.append(UInt8(ascii: "\n"))
        if buffer.count >= flushThreshold { try flush() }
    }

    func flush() throws(WriteError) {
        var offset = 0
        while offset < buffer.count {
            let written = buffer.withUnsafeBytes { bytes in
                write(fd, bytes.baseAddress! + offset, bytes.count - offset)
            }
            if written < 0 {
                if errno == EINTR { continue }
                throw WriteError(description: "write failed: \(String(cString: strerror(errno)))")
            }
            offset += written
        }
        buffer.removeAll(keepingCapacity: true)
    }

    /// Flushes and, for `--out` files, closes the descriptor.
    func finish() throws(WriteError) {
        try flush()
        if ownsDescriptor, close(fd) != 0 {
            throw WriteError(description: "close failed: \(String(cString: strerror(errno)))")
        }
    }
}

@main
enum OracleCommand {
    static func main() async {
        let options: OracleOptions
        do {
            options = try OracleOptions.parse(CommandLine.arguments.dropFirst())
        } catch {
            fail("\(error)\n\n\(OracleOptions.usage)")
        }

        if options.showHelp {
            print(OracleOptions.usage)
            return
        }

        var paths = options.files
        for listFile in options.listFiles {
            switch readFile(atPath: listFile == "-" ? "/dev/stdin" : listFile) {
            case .success(let contents): paths += pathsFromList(contents)
            case .failure(let error): fail("cannot read --files-from \(listFile): \(error.message)")
            }
        }
        if paths.isEmpty {
            fail("no input files\n\n\(OracleOptions.usage)")
        }

        do {
            let sink = try OutputSink(path: options.outputPath)
            let jobs = options.jobs ?? max(1, sysconf(Int32(_SC_NPROCESSORS_ONLN)))
            try await renderReports(for: paths, jobs: jobs) { line in try sink.writeLine(line) }
            try sink.finish()
        } catch {
            fail("\(error)", exitCode: 1)
        }
    }

    private static func fail(_ message: String, exitCode: Int32 = 2) -> Never {
        var line = "swift-syntax-oracle: \(message)\n"
        line.withUTF8 { _ = write(STDERR_FILENO, $0.baseAddress, $0.count) }
        exit(exitCode)
    }
}
