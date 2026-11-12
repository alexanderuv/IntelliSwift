let x: Array<Array<Int>> = [] ; let s = "a\(b)c"
let nested = "outer \("inner \(value + 1) done") end"
let raw = #"raw \#(x) and \(notInterpolated) "quotes""#
let raw2 = ##"double ## \##(y) "#"##
let escapes = "tab\t newline\n quote\" backslash\\ unicode\u{1F600} 😀"
let multi = """
    first line
      indented \(x)
    last line \
    continued
    """
let rawMulti = #"""
    raw "multi" \#(z)
    """#
let empty = ""
let ch: Character = "c"
