package io.github.alexanderuv.intelliswift.lexer

/**
 * Character classes from swift-syntax's `Lexer/UnicodeScalarExtensions.swift` and
 * `CharacterInfo.swift`. All functions take Unicode code points.
 */
internal object CharacterInfo {
    fun isAsciiIdentifierStart(c: Int): Boolean =
        (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || c == '_'.code

    fun isAsciiIdentifierContinue(c: Int): Boolean =
        isAsciiIdentifierStart(c) || (c in '0'.code..'9'.code) || c == '$'.code

    fun isDigit(c: Int): Boolean = c in '0'.code..'9'.code

    fun isHexDigit(c: Int): Boolean =
        isDigit(c) || (c in 'a'.code..'f'.code) || (c in 'A'.code..'F'.code)

    fun isLetter(c: Int): Boolean = Character.isLetter(c)

    fun isIdentifierContinuation(c: Int): Boolean {
        if (c < 0x80) return isAsciiIdentifierContinue(c)
        return c == 0x00A8 || c == 0x00AA || c == 0x00AD || c == 0x00AF ||
            (c in 0x00B2..0x00B5) || (c in 0x00B7..0x00BA) || (c in 0x00BC..0x00BE) ||
            (c in 0x00C0..0x00D6) || (c in 0x00D8..0x00F6) || (c in 0x00F8..0x00FF) ||
            (c in 0x0100..0x167F) || (c in 0x1681..0x180D) || (c in 0x180F..0x1FFF) ||
            (c in 0x200B..0x200D) || (c in 0x202A..0x202E) || (c in 0x203F..0x2040) || c == 0x2054 ||
            (c in 0x2060..0x206F) || (c in 0x2070..0x218F) || (c in 0x2460..0x24FF) || (c in 0x2776..0x2793) ||
            (c in 0x2C00..0x2DFF) || (c in 0x2E80..0x2FFF) || (c in 0x3004..0x3007) || (c in 0x3021..0x302F) ||
            (c in 0x3031..0x303F) || (c in 0x3040..0xD7FF) || (c in 0xF900..0xFD3D) || (c in 0xFD40..0xFDCF) ||
            (c in 0xFDF0..0xFE44) || (c in 0xFE47..0xFFF8) ||
            (c in 0x10000..0xEFFFD && (c and 0xFFFF) <= 0xFFFD)
    }

    fun isIdentifierStart(c: Int): Boolean {
        if (c < 0x80) return isAsciiIdentifierStart(c)
        if (!isIdentifierContinuation(c)) return false
        return !((c in 0x0300..0x036F) || (c in 0x1DC0..0x1DFF) || (c in 0x20D0..0x20FF) || (c in 0xFE20..0xFE2F))
    }

    fun isOperatorStart(c: Int): Boolean {
        if (c < 0x80) {
            return when (c.toChar()) {
                '/', '=', '-', '+', '*', '%', '<', '>', '!', '&', '|', '^', '~', '.', '?' -> true
                else -> false
            }
        }
        return (c in 0x00A1..0x00A7) ||
            c == 0x00A9 || c == 0x00AB || c == 0x00AC || c == 0x00AE ||
            c == 0x00B0 || c == 0x00B1 || c == 0x00B6 || c == 0x00BB ||
            c == 0x00BF || c == 0x00D7 || c == 0x00F7 ||
            c == 0x2016 || c == 0x2017 || (c in 0x2020..0x2027) ||
            (c in 0x2030..0x203E) || (c in 0x2041..0x2053) ||
            (c in 0x2055..0x205E) || (c in 0x2190..0x23FF) ||
            (c in 0x2500..0x2775) || (c in 0x2794..0x2BFF) ||
            (c in 0x2E00..0x2E7F) || (c in 0x3001..0x3003) ||
            (c in 0x3008..0x3030)
    }

    fun isOperatorContinuation(c: Int): Boolean {
        if (isOperatorStart(c)) return true
        return (c in 0x0300..0x036F) || (c in 0x1DC0..0x1DFF) || (c in 0x20D0..0x20FF) ||
            (c in 0xFE00..0xFE0F) || (c in 0xFE20..0xFE2F) || (c in 0xE0100..0xE01EF)
    }

    fun isPrintableAscii(c: Int): Boolean = c in 0x20 until 0x7F
}
