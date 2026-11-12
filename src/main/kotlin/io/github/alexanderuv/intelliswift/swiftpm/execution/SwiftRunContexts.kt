package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes
import io.github.alexanderuv.intelliswift.psi.SwiftFile
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestScope
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind

/** What a Swift Test configuration created from an editor or project view location runs. */
data class SwiftTestContext(
    val packageDirectory: String,
    val scope: SwiftTestScope,
    val module: String?,
    val suite: String?,
    val name: String?,
    /** The element the configuration was created from. */
    val element: PsiElement,
    /** The test node URL, for the gutter's test state icon; null for a whole package or target. */
    val locationUrl: String?,
)

/** What a Swift Run configuration created from an editor or project view location runs. */
data class SwiftRunContext(val packageDirectory: String, val product: String, val element: PsiElement)

/** Finds what to run or test at a location, for the gutter markers and the configuration producers. */
object SwiftRunContexts {

    /** The tests at [element]: the innermost test declaration, or a test target or package directory. */
    fun test(element: PsiElement): SwiftTestContext? {
        if (element is PsiDirectory) return directory(element)
        val declaration = SwiftTestDeclarations.enclosing(element) ?: return null
        return test(declaration)
    }

    fun test(declaration: SwiftTestDeclaration): SwiftTestContext? {
        val file = declaration.declaration.containingFile?.virtualFile ?: return null
        val target = SwiftPackageDescriptions.getInstance(declaration.declaration.project).targetOf(file) ?: return null
        if (target.kind != null && !target.isTest) return null
        val module = target.moduleName
        val (scope, suite, name) = when (declaration) {
            is SwiftTestDeclaration.XCTestClass -> Triple(SwiftTestScope.SUITE, declaration.className, null)
            is SwiftTestDeclaration.XCTestMethod -> Triple(SwiftTestScope.TEST, declaration.className, declaration.method)
            is SwiftTestDeclaration.TestingSuite -> Triple(SwiftTestScope.SUITE, declaration.components.joinToString("/"), null)
            is SwiftTestDeclaration.TestingFunction -> Triple(
                SwiftTestScope.TEST,
                declaration.components.dropLast(1).joinToString("/").ifEmpty { null },
                declaration.components.last(),
            )
        }
        return SwiftTestContext(target.packageDir.path, scope, module, suite, name, declaration.declaration,
            declaration.path(module).locationUrl)
    }

    private fun directory(directory: PsiDirectory): SwiftTestContext? {
        val dir = directory.virtualFile
        val packageDir = SwiftPackageDescriptions.packageRoot(dir) ?: return null
        if (packageDir == dir) return SwiftTestContext(dir.path, SwiftTestScope.ALL, null, null, null, directory, null)
        // A test target's directory in the standard layout, `Tests/<Target>`: ask about one of its files.
        if (dir.parent?.parent != packageDir) return null
        val probe = dir.children.firstOrNull { !it.isDirectory } ?: dir.children.firstOrNull() ?: return null
        val target = SwiftPackageDescriptions.getInstance(directory.project).targetOf(probe) ?: return null
        if (!target.isTest || dir.name != target.targetName) return null
        return SwiftTestContext(packageDir.path, SwiftTestScope.TARGET, target.moduleName, null, null, directory, null)
    }

    /** The executable at [element]: a `main.swift` file or a `@main` type of an executable target. */
    fun run(element: PsiElement): SwiftRunContext? {
        val file = element.containingFile as? SwiftFile ?: return null
        val entryPoint: PsiElement = when {
            file.name == MAIN_FILE -> file
            element is PsiFile -> file.declarations.filterIsInstance<SwiftNamedDeclaration>().firstOrNull(SwiftTestDeclarations::isMainType)
            else -> generateSequence(PsiTreeUtil.getParentOfType(element, SwiftNamedDeclaration::class.java, false)) {
                PsiTreeUtil.getParentOfType(it, SwiftNamedDeclaration::class.java, true)
            }.firstOrNull(SwiftTestDeclarations::isMainType)
        } ?: return null
        return runContext(file, entryPoint)
    }

    /** The executable of a `@main` type, given its declaration. */
    fun runOfMainType(declaration: SwiftNamedDeclaration): SwiftRunContext? {
        if (!SwiftTestDeclarations.isMainType(declaration)) return null
        return runContext(declaration.containingFile as? SwiftFile ?: return null, declaration)
    }

    /** The executable of `main.swift`, if [element] is the first token of one. */
    fun runOfMainFile(element: PsiElement): SwiftRunContext? {
        val file = element.containingFile as? SwiftFile ?: return null
        if (file.name != MAIN_FILE || element.firstChild != null || firstToken(file) != element) return null
        return runContext(file, file)
    }

    private fun runContext(file: SwiftFile, entryPoint: PsiElement): SwiftRunContext? {
        val virtualFile = file.virtualFile ?: return null
        val target = SwiftPackageDescriptions.getInstance(file.project).targetOf(virtualFile) ?: return null
        val product = when (target.kind) {
            SwiftTargetKind.EXECUTABLE -> target.executableProducts.firstOrNull() ?: target.targetName
            // The package is not described yet: by convention, an executable product is named after its target.
            null -> target.targetName
            else -> return null
        }
        return SwiftRunContext(target.packageDir.path, product, entryPoint)
    }

    private fun firstToken(file: SwiftFile): PsiElement? {
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null && (leaf.node.elementType == SwiftTokenTypes.WHITE_SPACE || leaf.node.elementType in SwiftTokenTypes.COMMENTS ||
                leaf.node.elementType == SwiftTokenTypes.SHEBANG || leaf.textLength == 0)) {
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return leaf
    }

    private const val MAIN_FILE = "main.swift"
}
