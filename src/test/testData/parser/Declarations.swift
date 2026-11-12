import Foundation
@testable import struct MyModule.Thing

public protocol Shape: Sendable {
    associatedtype Unit: Numeric = Double
    var area: Unit { get }
    func scaled(by factor: Unit) -> Self
    static func make() throws -> Self
}

public struct Circle<T: BinaryFloatingPoint>: Shape where T: Sendable {
    public typealias Unit = T
    private(set) var radius: T
    public var area: T { .pi * radius * radius }

    public init(radius: T = 1) {
        self.radius = radius
    }

    public func scaled(by factor: T) -> Circle { Circle(radius: radius * factor) }
    public static func make() -> Circle { Circle() }

    subscript(index: Int) -> T {
        get { radius }
        set { radius = newValue }
    }
}

final class Box<Value>: CustomStringConvertible {
    var value: Value {
        willSet { print(newValue) }
        didSet { print(oldValue) }
    }
    lazy var description: String = "\(value)"
    init(_ value: Value) { self.value = value }
    convenience init?(maybe value: Value?) {
        guard let value else { return nil }
        self.init(value)
    }
    deinit {}
}

indirect enum Tree<Element> {
    case leaf
    case node(left: Tree, Element, right: Tree)
    case a, b(Int) = 0
}

actor Counter {
    private var count = 0
    func increment() -> Int { count += 1; return count }
}

extension Array where Element: Equatable {
    mutating func removeAll(of element: Element) { removeAll { $0 == element } }
}

infix operator <=>: ComparisonPrecedence
precedencegroup PipePrecedence {
    associativity: left
    higherThan: AssignmentPrecedence
}

prefix func <=> (x: Int) -> Int { x }

@freestanding(expression)
public macro stringify<T>(_ value: T) -> (T, String) = #externalMacro(module: "Macros", type: "StringifyMacro")
