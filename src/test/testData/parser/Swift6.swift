// Typed throws (SE-0413)
enum ParseError: Error { case bad }
func parse(_ s: String) throws(ParseError) -> Int { throw .bad }
let handler: () throws(ParseError) -> Void = {}

// Noncopyable types and ownership (SE-0390, SE-0377, SE-0427)
struct FileHandle: ~Copyable {
    consuming func close() { discard self }
    borrowing func peek() -> Int { 0 }
    deinit {}
}
func take(_ handle: consuming FileHandle, _ other: borrowing FileHandle) {}
struct Wrapper<T: ~Copyable>: ~Copyable {}

// Parameter packs (SE-0393)
func all<each T: Equatable>(_ lhs: repeat each T, _ rhs: repeat each T) -> Bool {
    for (l, r) in repeat (each lhs, each rhs) {
        guard l == r else { return false }
    }
    return true
}

// if and switch expressions (SE-0380)
let sign = if x < 0 { -1 } else if x == 0 { 0 } else { 1 }
let name = switch sign {
case -1: "negative"
case 0: "zero"
default: "positive"
}

// Concurrency
nonisolated(unsafe) var globalCounter = 0
@MainActor final class Model {
    nonisolated func id() -> Int { 1 }
    func run() async throws {
        async let a = fetch()
        let results = try await [a]
        await withTaskGroup(of: Int.self) { group in
            group.addTask { @Sendable in 1 }
        }
        _ = results
    }
}
func send(_ value: sending NonSendable) -> sending NonSendable { value }
@concurrent func work() async {}
func later(_ body: nonisolated(nonsending) () async -> Void) async {}

// Macros
@attached(member, names: named(init))
macro Init() = #externalMacro(module: "M", type: "InitMacro")
#warning("check this")

// Value generics and inline arrays (SE-0452, SE-0453)
struct Vector<let count: Int, Element> {
    var storage: [count of Element]
}
let fixed: [3 of Int] = [1, 2, 3]
let negative: Vector<-1, Int>? = nil

// Raw identifiers (SE-0451)
func `test addition works`() {}

// Regex literals (SE-0354)
let digits = /\d+/
let extended = #/
  (?<year>\d{4}) - (?<month>\d{2})
/#

// Availability and conditional compilation
#if compiler(>=6.0) && canImport(Synchronization)
import Synchronization
#elseif os(Linux)
import Glibc
#else
#error("unsupported")
#endif

func check() {
    if #available(macOS 15, iOS 18, *) {}
    guard #unavailable(macOS 14) else { return }
}
