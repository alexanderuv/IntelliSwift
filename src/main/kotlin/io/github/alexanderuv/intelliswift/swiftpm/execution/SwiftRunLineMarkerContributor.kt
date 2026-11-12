package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration

/**
 * Run markers in the gutter: on XCTest case classes and their test methods, on Swift Testing
 * suites and `@Test` functions (with the state of their last run), and on `@main` types and the
 * first line of `main.swift` in executable targets. The actions come from the configuration
 * producers, which create configurations for the same elements.
 */
class SwiftRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null) return null
        SwiftTestDeclarations.ofNameIdentifier(element)?.let { declaration ->
            val context = SwiftRunContexts.test(declaration) ?: return null
            val icon = context.locationUrl?.let { getTestStateIcon(it, element.project, declaration.isGroup) }
                ?: AllIcons.RunConfigurations.TestState.Run
            return withExecutorActions(icon)
        }
        val declaration = element.parent as? SwiftNamedDeclaration
        if (declaration != null && declaration.nameIdentifier == element && SwiftRunContexts.runOfMainType(declaration) != null) {
            return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
        }
        if (SwiftRunContexts.runOfMainFile(element) != null) return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
        return null
    }

    override fun isDumbAware(): Boolean = true
}
