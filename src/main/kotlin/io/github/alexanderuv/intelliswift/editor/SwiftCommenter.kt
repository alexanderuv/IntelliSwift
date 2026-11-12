package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T

class SwiftCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun getLineCommentTokenType(): IElementType = T.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = T.BLOCK_COMMENT

    override fun getDocumentationCommentTokenType(): IElementType = T.DOC_BLOCK_COMMENT
    override fun getDocumentationCommentPrefix(): String = "/**"
    override fun getDocumentationCommentLinePrefix(): String = "*"
    override fun getDocumentationCommentSuffix(): String = "*/"

    override fun getDocumentationLineCommentTokenType(): IElementType = T.DOC_LINE_COMMENT
    override fun getDocumentationLineCommentPrefix(): String = "///"

    override fun isDocumentationComment(element: PsiComment): Boolean =
        element.tokenType == T.DOC_BLOCK_COMMENT || element.tokenType == T.DOC_LINE_COMMENT

    override fun isDocumentationLineComment(element: PsiComment): Boolean = element.tokenType == T.DOC_LINE_COMMENT
}
