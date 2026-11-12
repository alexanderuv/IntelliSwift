import Testing
import XCTest
@testable import Probe

struct Outer {
    @Suite struct Inner {
        @Test func deep() { #expect(one() == 1) }
    }
    @Test(arguments: [1, 2, 3]) func cases(n: Int) { #expect(n != 2) }
}

extension Outer {
    @Test func fromExtension() {}
}

@Test func known() { withKnownIssue { #expect(Bool(false)) } }
@Test func thrower() throws { throw NSError(domain: "x", code: 1) }

@Suite(.disabled("Off for now")) struct Off {
    @Test func a() {}
    @Suite struct Deeper {
        @Test func b() {}
    }
}

struct Failure: Error {}

@Suite struct Throwing {
    init() throws { throw Failure() }
    @Test func t() {}
}

@Test(arguments: ["x"]) func unlabeled(_ s: String) { print("printed \(s)") }

class Base: XCTestCase {}
final class Derived: Base {
    func testInherited() { XCTAssertTrue(true) }
}
