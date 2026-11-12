import Foundation
@testable import OracleCore
import SwiftParser
import XCTest

final class OracleCoreTests: XCTestCase {
    private static let fixtures = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Fixtures")

    private func report(_ source: String, path: String = "test.swift") throws -> [String: Any] {
        let bytes = FileReport.render(path: path, source: Array(source.utf8))
        let object = try JSONSerialization.jsonObject(with: Data(bytes))
        return try XCTUnwrap(object as? [String: Any])
    }

    private func tokens(_ source: String) throws -> [[String]] {
        try XCTUnwrap(report(source)["tokens"] as? [[String]])
    }

    private func decls(_ source: String) throws -> [[String: Any]] {
        try XCTUnwrap(report(source)["decls"] as? [[String: Any]])
    }

    func testKeysAreInDocumentedOrder() throws {
        let line = String(decoding: FileReport.render(path: "a", source: Array("x".utf8)), as: UTF8.self)
        XCTAssertTrue(line.hasPrefix(#"{"path":"a","tokens":["#), line)
        XCTAssertTrue(line.contains(#"],"decls":["#), line)
        XCTAssertTrue(line.hasSuffix(#"],"diagnostics":[]}"#), line)
        XCTAssertFalse(line.contains("\n"))
    }

    func testGenericsAndInterpolation() throws {
        let actual = try tokens(#"let x: Array<Array<Int>> = [] ; let s = "a\(b)c""#)
        XCTAssertEqual(actual, [
            ["keyword:let", "let"], ["identifier", "x"], ["colon", ":"],
            ["identifier", "Array"], ["leftAngle", "<"], ["identifier", "Array"], ["leftAngle", "<"],
            ["identifier", "Int"], ["rightAngle", ">"], ["rightAngle", ">"], ["equal", "="],
            ["leftSquare", "["], ["rightSquare", "]"], ["semicolon", ";"],
            ["keyword:let", "let"], ["identifier", "s"], ["equal", "="],
            ["stringQuote", "\""], ["stringSegment", "a"], ["backslash", "\\"], ["leftParen", "("],
            ["identifier", "b"], ["rightParen", ")"], ["stringSegment", "c"], ["stringQuote", "\""],
        ])
    }

    func testContextualKeywordsUseTheirRemappedKind() throws {
        let kinds = try tokens("var v: some P { get { 0 } set {} }").map { $0[0] }
        XCTAssertEqual(kinds.filter { $0.hasPrefix("keyword:") },
                       ["keyword:var", "keyword:some", "keyword:get", "keyword:set"])
    }

    func testMissingTokensAreExcluded() throws {
        let report = try report("func f(")
        XCTAssertEqual(report["tokens"] as? [[String]], [["keyword:func", "func"], ["identifier", "f"], ["leftParen", "("]])
        XCTAssertFalse((report["diagnostics"] as? [Any] ?? []).isEmpty)
    }

    func testDeclSkeletonNamesAndNesting() throws {
        let skeleton = declSkeleton(of: Parser.parse(source: """
            import os.log
            extension Array<Int> {
                var a, b: Int
                var (c, d) = (1, 2)
                var p: Int { get { 0 } }
                func f() { struct Inner {} }
            }
            enum E { case x, y }
            #if DEBUG
            class C {}
            #endif
            """))
        XCTAssertEqual(skeleton, [
            DeclNode(kind: "ImportDecl", name: "os.log"),
            DeclNode(kind: "ExtensionDecl", name: "Array<Int>", children: [
                DeclNode(kind: "VariableDecl", name: "a,b"),
                DeclNode(kind: "VariableDecl", name: nil),
                DeclNode(kind: "VariableDecl", name: "p", children: [DeclNode(kind: "AccessorDecl", name: "get")]),
                DeclNode(kind: "FunctionDecl", name: "f", children: [DeclNode(kind: "StructDecl", name: "Inner")]),
            ]),
            DeclNode(kind: "EnumDecl", name: "E", children: [DeclNode(kind: "EnumCaseDecl", name: "x,y")]),
            DeclNode(kind: "IfConfigDecl", name: nil, children: [DeclNode(kind: "ClassDecl", name: "C")]),
        ])
    }

    func testDiagnosticsCarryOneBasedLocations() throws {
        let diagnostics = try XCTUnwrap(report("let x = \n")["diagnostics"] as? [[String: Any]])
        XCTAssertEqual(diagnostics.count, 1)
        XCTAssertEqual(diagnostics.first?["line"] as? Int, 1)
        // The missing expression sits after the trailing space: byte offset 8, i.e. column 9.
        XCTAssertEqual(diagnostics.first?["column"] as? Int, 9)
        XCTAssertEqual(diagnostics.first?["severity"] as? String, "error")
    }

    func testJSONStringEscaping() throws {
        var writer = JSONWriter()
        writer.string("q\" b\\ \n\r\t\u{08}\u{0C}\u{00}\u{1F}\u{7F} é 😀 \u{2028}")
        let text = String(decoding: writer.bytes, as: UTF8.self)
        XCTAssertEqual(text, #""q\" b\\ \n\r\t\b\f\u0000\u001f"# + "\u{7F} é 😀 \u{2028}\"")
        let decoded = try JSONSerialization.jsonObject(with: Data(writer.bytes), options: .fragmentsAllowed)
        XCTAssertEqual(decoded as? String, "q\" b\\ \n\r\t\u{08}\u{0C}\u{00}\u{1F}\u{7F} é 😀 \u{2028}")
    }

    func testUnreadableFileProducesErrorObject() throws {
        let line = FileReport.render(path: "/definitely/not/here.swift")
        XCTAssertEqual(String(decoding: line, as: UTF8.self),
                       #"{"path":"/definitely/not/here.swift","error":"No such file or directory"}"#)
    }

    func testFixturesProduceValidJSONAndOnlyErrorsFixtureHasErrors() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.fixtures.path)
            .filter { $0.hasSuffix(".swift") }
            .sorted()
        XCTAssertFalse(names.isEmpty)
        for name in names {
            let bytes = FileReport.render(path: Self.fixtures.appendingPathComponent(name).path)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(bytes)) as? [String: Any], name)
            XCTAssertNil(object["error"], name)
            let diagnostics = try XCTUnwrap(object["diagnostics"] as? [Any], name)
            switch name {
            case "Errors.swift", "ControlChars.swift": XCTAssertFalse(diagnostics.isEmpty, name)
            default: XCTAssertTrue(diagnostics.isEmpty, "\(name): \(diagnostics)")
            }
        }
    }

    func testOptionParsing() throws {
        let options = try OracleOptions.parse(["a.swift", "--out", "o.jsonl", "--files-from", "l.txt",
                                               "--jobs", "3", "--", "--weird.swift"])
        XCTAssertEqual(options.files, ["a.swift", "--weird.swift"])
        XCTAssertEqual(options.listFiles, ["l.txt"])
        XCTAssertEqual(options.outputPath, "o.jsonl")
        XCTAssertEqual(options.jobs, 3)
        XCTAssertThrowsError(try OracleOptions.parse(["--out"]))
        XCTAssertThrowsError(try OracleOptions.parse(["--jobs", "0"]))
        XCTAssertThrowsError(try OracleOptions.parse(["--bogus"]))
    }

    func testPathsFromList() {
        XCTAssertEqual(pathsFromList(Array("a.swift\r\n\n b c.swift\nlast".utf8)), ["a.swift", " b c.swift", "last"])
    }

    func testOrderedConcurrentRendering() async throws {
        let paths = (0..<200).map { "/missing/\($0).swift" }
        var emitted: [String] = []
        try await renderReports(for: paths, jobs: 4) { line in
            let object = try JSONSerialization.jsonObject(with: Data(line)) as? [String: Any]
            emitted.append(object?["path"] as? String ?? "")
        }
        XCTAssertEqual(emitted, paths)
    }
}
