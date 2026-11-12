import Testing
@testable import Greeter

@Suite struct GreeterSuite {
    @Test func loudGreeting() {
        #expect(Greeter(name: "Ada").greeting(loud: true) == "HELLO, ADA!")
    }

    @Test("names", arguments: ["Ada", "Grace"])
    func names(name: String) {
        #expect(Greeter(name: name).greeting().hasPrefix("Hello"))
    }
}

@Test func topLevel() {
    #expect(1 + 1 == 2)
}

@Suite("Edge cases") struct EdgeCases {
    @Test func failingExpectation() {
        #expect(Greeter(name: "Ada").greeting() == "nope")
    }

    @Test(.disabled("Not ready"))
    func disabled() {}
}
