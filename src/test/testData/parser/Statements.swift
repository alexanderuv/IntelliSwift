func statements(values: [Int?], text: String) throws {
    outer: for case let value? in values where value > 0 {
        while value > 10 { continue outer }
        repeat { break } while false
    }
    for i in stride(from: 0, to: 10, by: 2) { _ = i }
    if let first = values.first, let first, case .some(let v) = first, v > 1 {
    } else if text.isEmpty {
    }
    guard var mutable = values.last else { throw MyError.missing }
    mutable = nil
    switch (text, values.count) {
    case ("a", _), ("b", 1...3):
        fallthrough
    case let (t, n) where n > t.count:
        break
    case (_, .zero):
        print("zero")
    @unknown default:
        return
    }
    do {
        try risky()
    } catch let error as MyError where error.code == 1 {
    } catch MyError.missing, MyError.invalid(_) {
    } catch {
        print(error)
    }
    defer { cleanup() }
    do throws(MyError) { try risky() }
}
