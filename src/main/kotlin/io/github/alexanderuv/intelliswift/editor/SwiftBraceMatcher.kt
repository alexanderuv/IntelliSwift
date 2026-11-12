package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/**
 * Matches `()`, `[]` and `{}`.
 *
 * Generic angle brackets are not matched: the platform matches braces on the editor highlighter's
 * token stream, which is the plain lexer, and `<`/`>` only become [T.LANGLE]/[T.RANGLE] in the
 * parse tree. In the lexer they are ordinary operators, indistinguishable from `a < b`.
 */
class SwiftBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || contextType in ALLOWED_BEFORE

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int {
        val brace = file.findElementAt(openingBraceOffset) ?: return openingBraceOffset
        val block = brace.parent ?: return openingBraceOffset
        if (block.node.elementType !in BLOCKS) return openingBraceOffset
        // Show the declaration or statement that owns the block, not the block itself.
        val owner = block.parent ?: return openingBraceOffset
        if (owner is PsiFile) return openingBraceOffset
        var child = owner.node.firstChildNode
        while (child != null && (child.elementType == T.WHITE_SPACE || child.elementType in T.COMMENTS)) child = child.treeNext
        return child?.startOffset ?: openingBraceOffset
    }

    companion object {
        private val PAIRS = arrayOf(
            BracePair(T.LPAR, T.RPAR, false),
            BracePair(T.LBRACKET, T.RBRACKET, false),
            BracePair(T.LBRACE, T.RBRACE, true),
        )

        private val ALLOWED_BEFORE = TokenSet.orSet(
            T.COMMENTS,
            TokenSet.create(T.WHITE_SPACE, T.RPAR, T.RBRACKET, T.RBRACE, T.COMMA, T.SEMICOLON, T.COLON, T.DOT),
        )

        private val BLOCKS = TokenSet.create(E.CODE_BLOCK, E.MEMBER_BLOCK, E.ACCESSOR_BLOCK)
    }
}
