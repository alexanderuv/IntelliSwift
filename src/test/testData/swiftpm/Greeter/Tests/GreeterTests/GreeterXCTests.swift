import XCTest
@testable import Greeter

final class GreeterXCTests: XCTestCase {
    func testGreeting() {
        XCTAssertEqual(Greeter(name: "Ada").greeting(), "Hello, Ada.")
    }

    func testFailing() {
        XCTAssertEqual(Greeter(name: "Ada").greeting(loud: true), "nope")
    }
}
