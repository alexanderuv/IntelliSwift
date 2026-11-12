package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywords
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

class SwiftNamesValidator : NamesValidator {
    /** Only reserved keywords; contextual ones (`get`, `async`, ...) are valid names. */
    override fun isKeyword(name: String, project: Project?): Boolean = SwiftKeywords.reserved(name) != null

    /** A single identifier token, plain or backticked (`` `class` ``). */
    override fun isIdentifier(name: String, project: Project?): Boolean {
        if (name.isEmpty()) return false
        val lexer = SwiftLexer()
        lexer.start(name, 0, name.length, 0)
        if (lexer.tokenType != T.IDENTIFIER || lexer.tokenEnd != name.length) return false
        lexer.advance()
        return lexer.tokenType == null
    }
}
