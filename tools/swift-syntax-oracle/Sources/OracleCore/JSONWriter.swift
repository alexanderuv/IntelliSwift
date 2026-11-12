/// Minimal append-only JSON serializer writing UTF-8 bytes into a buffer.
///
/// Hand-rolled instead of `JSONEncoder` because the oracle emits millions of tiny token pairs and
/// the encoder's per-value overhead dominates. Callers are responsible for structural correctness
/// (commas, nesting); this type only guarantees that every string is escaped per RFC 8259.
struct JSONWriter {
    private(set) var bytes: [UInt8] = []

    init(reservingCapacity capacity: Int = 0) {
        bytes.reserveCapacity(capacity)
    }

    mutating func raw(_ text: StaticString) {
        text.withUTF8Buffer { bytes.append(contentsOf: $0) }
    }

    mutating func raw(byte: UInt8) {
        bytes.append(byte)
    }

    mutating func null() {
        raw("null")
    }

    mutating func integer(_ value: Int) {
        bytes.append(contentsOf: String(value).utf8)
    }

    /// Writes `value` as a quoted JSON string. Non-ASCII scalars are emitted as raw UTF-8 (valid
    /// JSON); `"`, `\` and all C0 control characters are escaped.
    mutating func string(_ value: String) {
        bytes.append(UInt8(ascii: "\""))
        var value = value
        value.withUTF8 { utf8 in
            var runStart = 0
            for index in 0..<utf8.count {
                let byte = utf8[index]
                guard byte < 0x20 || byte == UInt8(ascii: "\"") || byte == UInt8(ascii: "\\") else {
                    continue
                }
                if runStart < index {
                    bytes.append(contentsOf: UnsafeBufferPointer(rebasing: utf8[runStart..<index]))
                }
                appendEscape(for: byte)
                runStart = index + 1
            }
            if runStart < utf8.count {
                bytes.append(contentsOf: UnsafeBufferPointer(rebasing: utf8[runStart..<utf8.count]))
            }
        }
        bytes.append(UInt8(ascii: "\""))
    }

    mutating func string(orNull value: String?) {
        if let value { string(value) } else { null() }
    }

    private mutating func appendEscape(for byte: UInt8) {
        switch byte {
        case UInt8(ascii: "\""): raw("\\\"")
        case UInt8(ascii: "\\"): raw("\\\\")
        case UInt8(ascii: "\n"): raw("\\n")
        case UInt8(ascii: "\r"): raw("\\r")
        case UInt8(ascii: "\t"): raw("\\t")
        case 0x08: raw("\\b")
        case 0x0C: raw("\\f")
        default:
            raw("\\u00")
            bytes.append(Self.hexDigits[Int(byte >> 4)])
            bytes.append(Self.hexDigits[Int(byte & 0x0F)])
        }
    }

    private static let hexDigits: [UInt8] = Array("0123456789abcdef".utf8)
}
