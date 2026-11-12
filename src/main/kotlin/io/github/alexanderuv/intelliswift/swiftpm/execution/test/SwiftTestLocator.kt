package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import io.github.alexanderuv.intelliswift.psi.SwiftFile
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftPackageDescriptions
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestDeclaration
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestDeclarations
import io.github.alexanderuv.intelliswift.swiftpm.execution.SwiftTestPath
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/**
 * Finds the source of a test tree node. Swift Testing nodes carry their declaration's source
 * location (`path:line:column`, from the event stream) as metainfo, which is used directly.
 * XCTest nodes, and Swift Testing nodes without it (from the test history), are looked up by
 * name: the words index gives the files that mention the class or function, and their PSI is
 * searched for the declaration.
 */
object SwiftTestLocator : SMTestLocator {

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> =
        getLocation(protocol, path, null, project, scope)

    override fun getLocation(
        protocol: String, path: String, metainfo: String?, project: Project, scope: GlobalSearchScope,
    ): List<Location<*>> {
        val testPath = SwiftTestPath.parse(protocol, path) ?: return emptyList()
        val elements = when (testPath) {
            is SwiftTestPath.XCTest -> findXCTest(project, scope, testPath)
            is SwiftTestPath.SwiftTesting -> listOfNotNull(metainfo?.let { atSourceLocation(project, it, testPath) })
                .ifEmpty { findSwiftTesting(project, scope, testPath) }
            is SwiftTestPath.Target -> emptyList()
        }
        return elements.map { PsiLocation.fromPsiElement(it) }
    }

    /** XCTest classes named [className] in [scope]. */
    fun findXCTestClasses(project: Project, scope: GlobalSearchScope, className: String, module: String? = null): List<SwiftNamedDeclaration> {
        val result = ArrayList<SwiftNamedDeclaration>()
        forFilesWithWord(project, scope, className) { file ->
            if (module != null && !inModule(file, module)) return@forFilesWithWord
            result += PsiTreeUtil.findChildrenOfType(file, SwiftNamedDeclaration::class.java)
                .filter { it.node.elementType == E.CLASS_DECL && it.name == className && SwiftTestDeclarations.isXCTestCase(it) }
        }
        return result
    }

    private fun findXCTest(project: Project, scope: GlobalSearchScope, path: SwiftTestPath.XCTest): List<PsiElement> {
        if (path.method == null) return findXCTestClasses(project, scope, path.className, path.module)
        val result = ArrayList<PsiElement>()
        forFilesWithWord(project, scope, path.method) { file ->
            if (path.module != null && !inModule(file, path.module)) return@forFilesWithWord
            result += declarations(file).filter { decl ->
                val test = SwiftTestDeclarations.of(decl)
                test is SwiftTestDeclaration.XCTestMethod && test.className == path.className && test.method == path.method
            }
        }
        return result
    }

    private fun findSwiftTesting(project: Project, scope: GlobalSearchScope, path: SwiftTestPath.SwiftTesting): List<PsiElement> {
        val word = path.components.last().substringBefore('(')
        val result = ArrayList<PsiElement>()
        forFilesWithWord(project, scope, word) { file ->
            if (!inModule(file, path.module)) return@forFilesWithWord
            result += declarations(file).filter { decl ->
                when (val test = SwiftTestDeclarations.of(decl)) {
                    is SwiftTestDeclaration.TestingFunction -> test.components == path.components
                    is SwiftTestDeclaration.TestingSuite -> test.components == path.components
                    else -> false
                }
            }
        }
        return result
    }

    /**
     * The declaration at a Swift Testing source location: for a function, the `@Test` attribute
     * inside it; for a suite, its `@Suite` attribute, or for a suite without one, its first test.
     */
    private fun atSourceLocation(project: Project, location: String, path: SwiftTestPath.SwiftTesting): PsiElement? {
        val match = LOCATION.matchEntire(location) ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath(match.groups[1]!!.value) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) as? SwiftFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        val offset = offsetOf(document, match.groups[2]!!.value.toInt(), match.groups[3]?.value?.toInt() ?: 1) ?: return null
        val name = path.components.last()
        var declaration = PsiTreeUtil.getParentOfType(psi.findElementAt(offset), SwiftNamedDeclaration::class.java, false)
        while (declaration != null) {
            val test = SwiftTestDeclarations.of(declaration)
            if (test is SwiftTestDeclaration.TestingFunction && test.components.last() == name) return declaration
            if (test is SwiftTestDeclaration.TestingSuite && test.components.last() == name) return declaration
            declaration = PsiTreeUtil.getParentOfType(declaration, SwiftNamedDeclaration::class.java, true)
        }
        return null
    }

    private fun offsetOf(document: Document, line: Int, column: Int): Int? {
        if (line < 1 || line > document.lineCount) return null
        val start = document.getLineStartOffset(line - 1)
        return (start + column - 1).coerceAtMost(document.getLineEndOffset(line - 1))
    }

    private fun inModule(file: PsiFile, module: String): Boolean {
        val virtualFile: VirtualFile = file.virtualFile ?: return true
        val target = SwiftPackageDescriptions.getInstance(file.project).targetOf(virtualFile) ?: return true
        return target.moduleName == module
    }

    private fun declarations(file: PsiFile): Collection<SwiftNamedDeclaration> =
        PsiTreeUtil.findChildrenOfType(file, SwiftNamedDeclaration::class.java)

    private fun forFilesWithWord(project: Project, scope: GlobalSearchScope, word: String, action: (SwiftFile) -> Unit) {
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, scope, { file ->
            if (file is SwiftFile) action(file)
            true
        }, true)
    }

    private val LOCATION = Regex("""(.+?):(\d+)(?::(\d+))?""")
}
