package io.github.alexanderuv.intelliswift.structure

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.alexanderuv.intelliswift.psi.SwiftDeclaration
import io.github.alexanderuv.intelliswift.psi.SwiftDeclarationPresentation
import io.github.alexanderuv.intelliswift.psi.SwiftFile
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

class SwiftStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is SwiftFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = SwiftStructureViewModel(psiFile, editor)
        }
    }
}

class SwiftStructureViewModel(file: SwiftFile, editor: Editor?) :
    StructureViewModelBase(file, editor, SwiftStructureViewElement(file)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    /** Only elements that appear in the tree, so "autoscroll from source" picks the nearest shown one. */
    override fun isSuitable(element: PsiElement?): Boolean = element != null && SwiftStructureViewElement.isShown(element)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        (element.value as? PsiElement)?.node?.elementType.let { it in E.TYPE_DECLARATIONS || it == E.EXTENSION_DECL }

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false
}

/**
 * One node of the Swift structure view: the file, a type or extension (with its members),
 * or a leaf declaration. A `var`/`let` contributes one node per bound name, an enum `case` one
 * node per element.
 */
class SwiftStructureViewElement(private val element: NavigatablePsiElement) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)
    override fun canNavigate(): Boolean = element.canNavigate()
    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

    override fun getAlphaSortKey(): String = when (element.node.elementType) {
        E.EXTENSION_DECL -> SwiftDeclarationPresentation.presentableText(element)?.removePrefix("extension ") ?: ""
        else -> SwiftDeclarationPresentation.presentableText(element) ?: ""
    }

    override fun getPresentation(): ItemPresentation {
        if (element is SwiftFile) return element.presentation ?: PresentationData(element.name, null, element.getIcon(0), null)
        return PresentationData(
            SwiftDeclarationPresentation.presentableText(element),
            SwiftDeclarationPresentation.locationText(element),
            SwiftDeclarationPresentation.icon(element),
            null,
        )
    }

    override fun getChildren(): Array<TreeElement> {
        val declarations = when {
            element is SwiftFile -> element.declarations
            element is SwiftDeclaration && hasMembers(element) -> element.members
            else -> return TreeElement.EMPTY_ARRAY
        }
        return declarations.flatMap(::entriesFor).map(::SwiftStructureViewElement).toTypedArray()
    }

    companion object {
        private val SHOWN_DECLARATIONS = setOf(
            E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL, E.PROTOCOL_DECL, E.EXTENSION_DECL,
            E.FUNCTION_DECL, E.INITIALIZER_DECL, E.DEINITIALIZER_DECL, E.SUBSCRIPT_DECL, E.TYPE_ALIAS_DECL,
            E.ASSOCIATED_TYPE_DECL, E.MACRO_DECL,
        )

        private fun hasMembers(decl: SwiftDeclaration): Boolean {
            val t = decl.node.elementType
            return t in E.TYPE_DECLARATIONS || t == E.EXTENSION_DECL
        }

        /** The tree nodes a declaration contributes. */
        private fun entriesFor(decl: SwiftDeclaration): List<NavigatablePsiElement> = when (decl.node.elementType) {
            E.VARIABLE_DECL, E.ENUM_CASE_DECL -> decl.bindings.filter { it.name != null }
            in SHOWN_DECLARATIONS -> if (SwiftDeclarationPresentation.presentableText(decl) != null) listOf(decl) else emptyList()
            else -> emptyList()
        }

        /** Whether [element] has its own node in the tree. */
        fun isShown(element: PsiElement): Boolean {
            if (element is SwiftFile) return true
            val type = element.node?.elementType ?: return false
            return when {
                type in SHOWN_DECLARATIONS -> element is SwiftDeclaration && (isTopLevelOrMember(element))
                type == E.ENUM_CASE_ELEMENT -> element.parent?.let(::isTopLevelOrMember) == true
                type == E.IDENTIFIER_PATTERN -> element is SwiftNamedDeclaration &&
                    SwiftDeclarationPresentation.variableDeclarationOf(element)?.let(::isTopLevelOrMember) == true
                else -> false
            }
        }

        private fun isTopLevelOrMember(element: PsiElement): Boolean =
            SwiftDeclarationPresentation.isMember(element) || SwiftDeclarationPresentation.isTopLevel(element)
    }
}
