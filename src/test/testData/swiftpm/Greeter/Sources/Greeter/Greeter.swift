import Utils

public struct Greeter {
    public let name: String
    public init(name: String) { self.name = name }
    public func greeting(loud: Bool = false) -> String {
        let text = "Hello, \(name)"
        return loud ? shout(text) : text + "."
    }
}
