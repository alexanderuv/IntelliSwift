package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/** Entry point: parses a whole Swift file into the PsiBuilder. */
class SwiftParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        val core = SwiftParserCore(builder)
        core.parseSourceFile()
        // Anything the grammar left unconsumed (it should not) is kept so the tree covers the text.
        if (!builder.eof()) {
            val rest = builder.mark()
            while (!builder.eof()) builder.advanceLexer()
            rest.error("Unexpected text")
        }
        file.done(root)
        return builder.treeBuilt
    }
}
