package io.github.alexanderuv.intelliswift.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.alexanderuv.intelliswift.lexer.SwiftLexer
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes
import io.github.alexanderuv.intelliswift.psi.SwiftFile
import io.github.alexanderuv.intelliswift.psi.SwiftPsiFactory

class SwiftParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = SwiftLexer()
    override fun createParser(project: Project?): PsiParser = SwiftParser()
    override fun getFileNodeType(): IFileElementType = SwiftElementTypes.FILE
    override fun getWhitespaceTokens(): TokenSet = SwiftTokenTypes.WHITESPACES
    override fun getCommentTokens(): TokenSet = SwiftTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = SwiftTokenTypes.STRING_CONTENT
    override fun createElement(node: ASTNode): PsiElement = SwiftPsiFactory.createElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = SwiftFile(viewProvider)
}
