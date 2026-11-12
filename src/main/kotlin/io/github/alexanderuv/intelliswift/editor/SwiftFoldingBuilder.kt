package io.github.alexanderuv.intelliswift.editor

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes as T
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/**
 * Folds brace-delimited bodies, multi-line comments, runs of `///` lines, runs of imports,
 * `#if` clauses and multi-line string literals. Purely syntactic, so it also works in dumb mode.
 */
class SwiftFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val out = ArrayList<FoldingDescriptor>()
        collect(root.node, document, out)
        return out.toTypedArray()
    }

    private fun collect(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        var child: ASTNode? = node.firstChildNode
        while (child != null) {
            val type = child.elementType
            val current: ASTNode = when {
                type in BRACED -> child.also { bracedRegion(it, document, out) }
                type == E.IF_CONFIG_CLAUSE -> child.also { ifConfigClauseRegion(it, document, out) }
                type == E.STRING_LITERAL_EXPR -> child.also { stringRegion(it, document, out) }
                type == T.BLOCK_COMMENT || type == T.DOC_BLOCK_COMMENT -> child.also { addIfMultiline(it, it.textRange, document, out) }
                type == T.DOC_LINE_COMMENT -> docLineRun(child, document, out)
                type == E.IMPORT_DECL -> importRun(child, document, out)
                else -> child
            }
            if (current.firstChildNode != null) collect(current, document, out)
            child = current.treeNext
        }
    }

    /** `{` ... `}`, where the braces are direct children of the node (code blocks, closures, member and accessor blocks, `switch`). */
    private fun bracedRegion(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        val open = node.findChildByType(T.LBRACE) ?: return
        val close = lastChildOfType(node, T.RBRACE) ?: return
        addIfMultiline(node, TextRange(open.startOffset, close.startOffset + close.textLength), document, out)
    }

    /** From the end of the `#if cond` / `#else` line to the end of the clause body. */
    private fun ifConfigClauseRegion(clause: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        val directive = clause.firstChildNode ?: return
        if (directive.elementType !in POUND_CLAUSE_STARTS) return
        val headerLine = document.getLineNumber(directive.startOffset)
        val start = document.getLineEndOffset(headerLine)
        val end = lastSignificant(clause)?.let { it.startOffset + it.textLength } ?: return
        if (end <= start) return
        addIfMultiline(clause, TextRange(start, end), document, out)
    }

    private fun stringRegion(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        var c = node.firstChildNode
        while (c != null && c.elementType == T.RAW_STRING_DELIMITER) c = c.treeNext
        if (c?.elementType != T.MULTILINE_STRING_QUOTE) return
        addIfMultiline(node, node.textRange, document, out)
    }

    /** Folds consecutive `///` lines; returns the last comment of the run. */
    private fun docLineRun(first: ASTNode, document: Document, out: MutableList<FoldingDescriptor>): ASTNode {
        var last = first
        var c = first.treeNext
        while (c != null) {
            if (c.elementType == T.WHITE_SPACE && lineBreaks(c.chars) == 1) { c = c.treeNext; continue }
            if (c.elementType != T.DOC_LINE_COMMENT) break
            last = c
            c = c.treeNext
        }
        if (last !== first) addIfMultiline(first, TextRange(first.startOffset, last.startOffset + last.textLength), document, out)
        return last
    }

    /** Folds the import paths of consecutive imports into `import ...`; returns the last import of the run. */
    private fun importRun(first: ASTNode, document: Document, out: MutableList<FoldingDescriptor>): ASTNode {
        var last = first
        var c = first.treeNext
        while (c != null) {
            if (c.elementType == T.WHITE_SPACE || c.elementType in T.COMMENTS) { c = c.treeNext; continue }
            if (c.elementType != E.IMPORT_DECL) break
            last = c
            c = c.treeNext
        }
        if (last === first) return last
        val keyword = generateSequence(first.firstChildNode) { it.treeNext }.firstOrNull { it.elementType == KW_IMPORT } ?: return last
        val start = generateSequence(keyword.treeNext) { it.treeNext }.firstOrNull { it.elementType != T.WHITE_SPACE } ?: return last
        val range = TextRange(start.startOffset, last.startOffset + last.textLength)
        if (!range.isEmpty) addIfMultiline(first, range, document, out)
        return last
    }

    private fun addIfMultiline(node: ASTNode, range: TextRange, document: Document, out: MutableList<FoldingDescriptor>) {
        if (range.isEmpty || range.endOffset > document.textLength) return
        if (document.getLineNumber(range.startOffset) == document.getLineNumber(range.endOffset)) return
        out += FoldingDescriptor(node, range)
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        T.BLOCK_COMMENT -> "/*...*/"
        T.DOC_BLOCK_COMMENT -> "/**...*/"
        T.DOC_LINE_COMMENT -> node.text.trimEnd().let { if (it.length > MAX_PLACEHOLDER) it.take(MAX_PLACEHOLDER) else it } + " ..."
        E.STRING_LITERAL_EXPR -> "\"\"\"...\"\"\""
        E.IMPORT_DECL, E.IF_CONFIG_CLAUSE -> "..."
        else -> "{...}"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = when (node.elementType) {
        E.IMPORT_DECL -> CodeFoldingSettings.getInstance().COLLAPSE_IMPORTS
        T.DOC_BLOCK_COMMENT, T.DOC_LINE_COMMENT -> CodeFoldingSettings.getInstance().COLLAPSE_DOC_COMMENTS
        else -> false
    }

    private fun lastChildOfType(node: ASTNode, type: com.intellij.psi.tree.IElementType): ASTNode? {
        var c = node.lastChildNode
        while (c != null && c.elementType != type) c = c.treePrev
        return c
    }

    private fun lastSignificant(node: ASTNode): ASTNode? {
        var c = node.lastChildNode
        while (c != null && c.elementType == T.WHITE_SPACE) c = c.treePrev
        return c
    }

    private fun lineBreaks(text: CharSequence): Int = text.count { it == '\n' }

    private companion object {
        val BRACED = TokenSet.create(E.CODE_BLOCK, E.MEMBER_BLOCK, E.ACCESSOR_BLOCK, E.CLOSURE_EXPR, E.SWITCH_EXPR, E.PRECEDENCE_GROUP_DECL)
        val POUND_CLAUSE_STARTS = TokenSet.create(T.POUND_IF, T.POUND_ELSEIF, T.POUND_ELSE)
        val KW_IMPORT = io.github.alexanderuv.intelliswift.lexer.KW.IMPORT
        const val MAX_PLACEHOLDER = 60
    }
}
