let a = 1 + 2 * 3 - -4
let b = x ?? y ?? 0
let c = flag ? first : second
let d = value as? Int, e = value as! String, f = value is Int
let g = obj?.child?.name!.count
let h = numbers.map { $0 * 2 }.filter { n in n > 2 }
let i = try? decoder.decode([String: Int].self, from: data)
let j = { [weak self, count = items.count] (x: Int, y) async throws -> Int in x + y }
let k = \Person.address.city
let l = items.sorted(by: <)
let m = "Hello, \(name)! You have \(count + 1) \"messages\"."
let n = #"raw \#(interpolated) "quoted""#
let o = """
    multi
    \(line) string
    """
let p: [Int: [String]] = [1: ["a"], 2: []]
let q = (1, label: "two", 3.0)
let r = Array<Int>(repeating: 0, count: 3)
let s = 0..<10, t = ...5, u = 1...
let v = &buffer
let w = Task { @MainActor in await work() }
button.onTap(perform: handle) { event in
    print(event)
} onCancel: {
    print("cancelled")
}
x = y
compute(.init(rawValue: 1), .zero, #line)
