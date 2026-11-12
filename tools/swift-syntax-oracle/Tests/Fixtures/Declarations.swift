#!/usr/bin/env swift
import Foundation
import struct Swift.Array
@testable import os.log

precedencegroup PowerPrecedence {
    higherThan: MultiplicationPrecedence
    associativity: right
}
infix operator ** : PowerPrecedence
prefix operator √
postfix operator ++

protocol Shape: AnyObject {
    associatedtype Unit
    var area: Double { get }
    var name: String { get set }
    func scaled(by factor: Double) -> Self
}

public struct Box<T: Equatable>: Equatable where T: Hashable {
    typealias Element = T
    var a, b: Int
    let (c, d) = (1, 2)
    var _ = 0
    var `class`: String = ""
    private(set) var count = 0 {
        willSet { print(newValue) }
        didSet(old) { print(old) }
    }
    var computed: some Collection<Int> {
        get { [1, 2] }
        set { _ = newValue }
    }
    subscript(index: Int) -> T? { nil }
    init(value: T) { self.value = value }
    init?() { return nil }
    deinit {}
    func method<U>(_ u: U) async throws -> any Sequence {
        struct Local { func inner() {} }
        let closure = { (x: Int) -> Int in
            func nested() {}
            return x
        }
        return []
    }
}

enum Direction: String, CaseIterable {
    case north, south
    case east = "E"
    indirect case node(Direction, Direction)
}

final class Node<Value>: NSObject, @unchecked Sendable {
    static func ** (lhs: Node, rhs: Node) -> Node { lhs }
    static prefix func √ (x: Node) -> Node { x }
    override init() {}
}

actor Counter {
    nonisolated let id = 0
    func increment() async {}
}

extension Array<Int>: Shape where Element: Comparable {
    convenience init(x: Int) {}
}

extension Swift.Dictionary.Keys {}

@freestanding(expression)
macro stringify<T>(_ value: T) -> (T, String) = #externalMacro(module: "M", type: "S")

#warning("check this")
#sourceLocation(file: "a.swift", line: 1)
#sourceLocation()

#if os(macOS) && swift(>=5.9)
func onlyMac() {}
#elseif canImport(UIKit)
import UIKit
#else
struct Fallback {}
#endif

let optional = value?.member!.other ?? 0
let generic = Dictionary<String, Array<Set<Int>>>()
let shifted = a >> 2 << 3 &+ b ... c ..< d
let result = -x + !y
var tuple = (a: 1, b: 2).0
let keyPath = \Box<Int>.count
let anon = $0 + $1
let wildcardPattern: Int = { _ in 0 }(1)
let consumed = consume x
let copied = copy y
func someAny(a: some P, b: any P, c: inout Int, d: borrowing Int, e: consuming Int) {}
let cast = (x as? Int, x as! Int, x is Int)
let inoutAmp = &value
let c = condition ? 1 : 2
let float = 1.5e-3 + 0x1p4 + 1_000
