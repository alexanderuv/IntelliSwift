package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.alexanderuv.intelliswift.lexer.SwiftKeywordTokenType
import io.github.alexanderuv.intelliswift.lexer.SwiftTokenTypes
import io.github.alexanderuv.intelliswift.psi.SwiftDeclaration
import io.github.alexanderuv.intelliswift.psi.SwiftExtensionDeclaration
import io.github.alexanderuv.intelliswift.psi.SwiftFile
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import io.github.alexanderuv.intelliswift.parser.SwiftElementTypes as E

/** A declaration that is a test, or a group of tests, of XCTest or Swift Testing. */
sealed interface SwiftTestDeclaration {
    val declaration: SwiftNamedDeclaration

    /** Whether it is a group (class or suite) rather than one test. */
    val isGroup: Boolean

    /** The test, or tests, of this declaration in test target [module]. */
    fun path(module: String): SwiftTestPath

    /** A short name for a run configuration. */
    val displayName: String

    data class XCTestClass(override val declaration: SwiftNamedDeclaration, val className: String) : SwiftTestDeclaration {
        override val isGroup get() = true
        override fun path(module: String) = SwiftTestPath.XCTest(module, className)
        override val displayName get() = className
    }

    data class XCTestMethod(override val declaration: SwiftNamedDeclaration, val className: String, val method: String) :
        SwiftTestDeclaration {
        override val isGroup get() = false
        override fun path(module: String) = SwiftTestPath.XCTest(module, className, method)
        override val displayName get() = "$className.$method"
    }

    /** A Swift Testing suite; [components] are its type names from the outermost, as in its test ID. */
    data class TestingSuite(override val declaration: SwiftNamedDeclaration, val components: List<String>) : SwiftTestDeclaration {
        override val isGroup get() = true
        override fun path(module: String) = SwiftTestPath.SwiftTesting(module, components)
        override val displayName get() = components.joinToString("/")
    }

    /** A Swift Testing `@Test` function; [components] end with its name as in its test ID, e.g. `names(name:)`. */
    data class TestingFunction(override val declaration: SwiftNamedDeclaration, val components: List<String>) : SwiftTestDeclaration {
        override val isGroup get() = false
        override fun path(module: String) = SwiftTestPath.SwiftTesting(module, components)
        override val displayName get() = components.joinToString("/")
    }
}

/**
 * Recognizes test and entry point declarations in the PSI.
 *
 * - XCTest: a class whose superclass is `XCTestCase`, directly or through other classes of the
 *   same file, and its `test…()` instance methods without parameters (also in extensions of the
 *   class in the same file).
 * - Swift Testing: `@Test` functions, at the top level or in a type, and suites: types marked
 *   `@Suite` or containing `@Test` functions or other suites.
 * - Entry points: types marked `@main`.
 */
object SwiftTestDeclarations {

    /** The test declaration named by [element], if [element] is the name identifier of one. */
    fun ofNameIdentifier(element: PsiElement): SwiftTestDeclaration? {
        val declaration = element.parent as? SwiftNamedDeclaration ?: return null
        if (declaration.nameIdentifier != element) return null
        return of(declaration)
    }

    /** The innermost test declaration containing [element]. */
    fun enclosing(element: PsiElement): SwiftTestDeclaration? {
        var current: PsiElement? = element
        while (current != null && current !is SwiftFile) {
            if (current is SwiftNamedDeclaration) of(current)?.let { return it }
            current = current.parent
        }
        return null
    }

    fun of(declaration: SwiftNamedDeclaration): SwiftTestDeclaration? {
        val type = declaration.node.elementType
        if (isLocal(declaration)) return null
        return when (type) {
            E.FUNCTION_DECL -> function(declaration)
            E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL -> {
                val name = declaration.name ?: return null
                when {
                    type == E.CLASS_DECL && isXCTestCase(declaration) -> SwiftTestDeclaration.XCTestClass(declaration, name)
                    isSuite(declaration) -> SwiftTestDeclaration.TestingSuite(declaration, typePath(declaration))
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun function(fn: SwiftNamedDeclaration): SwiftTestDeclaration? {
        val name = fn.name ?: return null
        if (hasAttribute(fn, "Test")) {
            val owner = enclosingTypePath(fn)
            return SwiftTestDeclaration.TestingFunction(fn, owner + "$name(${parameterLabels(fn).joinToString("") { "$it:" }})")
        }
        if (!name.startsWith("test") || parameterLabels(fn).isNotEmpty()) return null
        val modifiers = modifiers(fn)
        if ("static" in modifiers || "class" in modifiers || "private" in modifiers || "fileprivate" in modifiers) return null
        val owner = fn.parentDeclaration() ?: return null
        val className = when {
            owner is SwiftNamedDeclaration && isXCTestCase(owner) -> owner.name
            owner is SwiftExtensionDeclaration -> owner.extendedTypeText?.trim()?.takeIf { extended ->
                classesOf(fn.containingFile as? SwiftFile).any { it.name == extended && isXCTestCase(it) }
            }
            else -> null
        } ?: return null
        return SwiftTestDeclaration.XCTestMethod(fn, className, name)
    }

    /** Whether [declaration] carries `@main`. */
    fun isMainType(declaration: SwiftNamedDeclaration): Boolean =
        declaration.node.elementType in MAIN_TYPES && hasAttribute(declaration, "main")

    /** Whether [cls] inherits from `XCTestCase`, directly or through classes of the same file. */
    fun isXCTestCase(cls: SwiftNamedDeclaration, visited: Set<SwiftNamedDeclaration> = emptySet()): Boolean {
        if (cls.node.elementType != E.CLASS_DECL) return false
        val superclass = superclassName(cls) ?: return false
        if (superclass == "XCTestCase" || superclass == "XCTest.XCTestCase") return true
        val base = classesOf(cls.containingFile as? SwiftFile).firstOrNull { it.name == superclass && it != cls } ?: return false
        return base !in visited && isXCTestCase(base, visited + cls)
    }

    private fun isSuite(type: SwiftNamedDeclaration): Boolean {
        if (hasAttribute(type, "Suite")) return true
        return type.members.any { member ->
            when (member.node.elementType) {
                E.FUNCTION_DECL -> hasAttribute(member, "Test")
                in MAIN_TYPES -> member is SwiftNamedDeclaration && isSuite(member)
                else -> false
            }
        }
    }

    /** The first type in the inheritance clause, which for a class is its superclass if it has one. */
    private fun superclassName(cls: SwiftNamedDeclaration): String? {
        val clause = cls.node.findChildByType(E.INHERITANCE_CLAUSE) ?: return null
        val first = clause.findChildByType(E.INHERITED_TYPE) ?: return null
        return first.text.filterNot { it.isWhitespace() }
    }

    /** The classes declared in [file], computed once per file version. */
    private fun classesOf(file: SwiftFile?): List<SwiftNamedDeclaration> =
        if (file == null) emptyList()
        else CachedValuesManager.getCachedValue(file) {
            val classes = PsiTreeUtil.findChildrenOfType(file, SwiftNamedDeclaration::class.java).filter { it.node.elementType == E.CLASS_DECL }
            CachedValueProvider.Result.create(classes, file)
        }

    /** The names of the attributes of [declaration], without a `Testing.` or `Swift.` module prefix. */
    fun attributeNames(declaration: SwiftDeclaration): List<String> {
        val modifiers = declaration.node.findChildByType(E.MODIFIER_LIST) ?: return emptyList()
        return children(modifiers).filter { it.elementType == E.ATTRIBUTE }.mapNotNull { attribute ->
            val name = children(attribute).firstOrNull { it.elementType == E.IDENTIFIER_TYPE || it.elementType == E.MEMBER_TYPE }
                ?: return@mapNotNull null
            name.text.filterNot { it.isWhitespace() }.removePrefix("Testing.").removePrefix("Swift.")
        }.toList()
    }

    private fun hasAttribute(declaration: SwiftDeclaration, name: String) = name in attributeNames(declaration)

    private fun modifiers(declaration: SwiftDeclaration): List<String> {
        val modifiers = declaration.node.findChildByType(E.MODIFIER_LIST) ?: return emptyList()
        return children(modifiers).filter { it.elementType == E.DECL_MODIFIER }.mapNotNull { modifier ->
            children(modifier).firstOrNull { it.elementType is SwiftKeywordTokenType || it.elementType == SwiftTokenTypes.IDENTIFIER }?.text
        }.toList()
    }

    /** Argument labels of a function's parameters, `_` for unlabeled ones. */
    fun parameterLabels(fn: SwiftNamedDeclaration): List<String> {
        val clause = fn.node.findChildByType(E.FUNCTION_SIGNATURE)?.findChildByType(E.PARAMETER_CLAUSE) ?: return emptyList()
        return children(clause).filter { it.elementType == E.FUNCTION_PARAMETER }.map { parameter ->
            val label = children(parameter).firstOrNull { it.elementType != SwiftTokenTypes.WHITE_SPACE && it.elementType !in SwiftTokenTypes.COMMENTS }
            label?.text?.removeSurrounding("`") ?: "_"
        }.toList()
    }

    /** Names of the types enclosing [declaration], outermost first. */
    private fun enclosingTypePath(declaration: SwiftDeclaration): List<String> {
        val owner = declaration.parentDeclaration() ?: return emptyList()
        return when (owner) {
            is SwiftExtensionDeclaration -> owner.extendedTypeText?.split('.')?.map { it.trim() }.orEmpty()
            is SwiftNamedDeclaration -> typePath(owner)
            else -> emptyList()
        }
    }

    private fun typePath(type: SwiftNamedDeclaration): List<String> = enclosingTypePath(type) + listOfNotNull(type.name)

    /** The type or extension whose body declares [this], looking through `#if`. */
    private fun SwiftDeclaration.parentDeclaration(): SwiftDeclaration? {
        var p = parent
        while (p != null && p !is SwiftFile) {
            val type = p.node.elementType
            if (type == E.MEMBER_BLOCK) return p.parent as? SwiftDeclaration
            if (type != E.IF_CONFIG_CLAUSE && type != E.IF_CONFIG_DECL) return null
            p = p.parent
        }
        return null
    }

    /** Declarations inside function bodies or closures cannot be tests. */
    private fun isLocal(declaration: SwiftDeclaration): Boolean =
        generateSequence(declaration.parent) { it.parent }.takeWhile { it !is SwiftFile }
            .any { it.node.elementType == E.CODE_BLOCK || it.node.elementType == E.CLOSURE_EXPR || it.node.elementType == E.ACCESSOR_BLOCK }

    private fun children(node: ASTNode): Sequence<ASTNode> = generateSequence(node.firstChildNode) { it.treeNext }

    private val MAIN_TYPES = setOf(E.CLASS_DECL, E.STRUCT_DECL, E.ENUM_DECL, E.ACTOR_DECL)
}
