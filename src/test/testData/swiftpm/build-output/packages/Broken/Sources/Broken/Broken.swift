public struct Counter {
    public var count: Int = 0

    public func label(prefix: String, suffix: String) -> String {
        let unused = 42
        return prefix + String(count) + suffix
    }

    public func total() -> Int {
        let text: String = count
        return label(prefix: "n")
    }
}
