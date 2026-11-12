import Foundation

/// A shape.
protocol Shape {
    associatedtype Unit
    var area: Double { get }
    func scaled(by factor: Double) -> Self
}

public struct Circle: Shape, Equatable {
    typealias Unit = Double
    var radius: Double
    var x, y: Int
    let (a, b) = (1, 2)
    var area: Double { .pi * radius * radius }

    init(radius: Double) { self.radius = radius }
    init?(_ text: String) { return nil }
    subscript(index: Int) -> Double { radius }
    subscript(row r: Int, column c: Int) -> Double { radius }
    func scaled(by factor: Double) -> Circle {
        let local = 1
        return Circle(radius: radius * factor)
    }
    static func == (lhs: Circle, rhs: Circle) -> Bool { true }

    enum Kind {
        case small, large(size: Int)
        case huge
    }
}

final class Box<T> {
    deinit {}
}

actor Counter {}

extension Array where Element: Shape {
    func totalArea() -> Double { 0 }
}

extension Circle: CustomStringConvertible {
    var description: String { "" }
}

#if DEBUG
func debugOnly(_ x: Int, _ y: Int) {}
#else
func releaseOnly() {}
#endif

let globalValue = 1
func topLevel() {}
