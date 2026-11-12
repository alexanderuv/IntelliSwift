// Keeps the caret (at offset 0) off the import line, so the import region folds by default.
import <fold text='...' expand='false'>Foundation
import SwiftUI
@testable import Core</fold>

<fold text='/// Coordinates on a map. ...' expand='true'>/// Coordinates on a map.
/// Immutable.</fold>
struct Point <fold text='{...}' expand='true'>{
    var x: Double
    var y: Double

    var length: Double <fold text='{...}' expand='true'>{
        get <fold text='{...}' expand='true'>{
            (x * x + y * y).squareRoot()
        }</fold>
    }</fold>

    func scaled(by f: Double) -> Point <fold text='{...}' expand='true'>{
        let p = Point(x: x * f, y: y * f)
        return p
    }</fold>
}</fold>

<fold text='/*...*/' expand='true'>/*
 A block comment.
 */</fold>
/* one line */
func run() <fold text='{...}' expand='true'>{
    let values = [1, 2, 3].map <fold text='{...}' expand='true'>{ v in
        v * 2
    }</fold>
    let text = <fold text='"""..."""' expand='true'>"""
        multi
        line
        """</fold>
    switch values.count <fold text='{...}' expand='true'>{
    case 0: print("none")
    default: print(text)
    }</fold>
    if values.isEmpty { return }
}</fold>

#if DEBUG<fold text='...' expand='true'>
let mode = "debug"
let verbose = true</fold>
#elseif TESTING
#else<fold text='...' expand='true'>
let mode = "release"
let verbose = false</fold>
#endif
