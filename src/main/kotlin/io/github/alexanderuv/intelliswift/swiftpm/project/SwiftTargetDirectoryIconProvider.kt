package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.ide.IconProvider
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.SourceFolder
import com.intellij.openapi.roots.ui.configuration.ModuleSourceRootEditHandler
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import org.jetbrains.jps.model.JpsElement
import org.jetbrains.jps.model.module.JpsTypedModuleSourceRoot
import javax.swing.Icon

/**
 * Shows a SwiftPM target's directory with its source root's icon: the source folder for a
 * production target, the test folder for a test target.
 *
 * A target's directory is both its module's content root and its source root. The Java plugin's
 * directory icon provider shows a content root with its module type's icon before it looks at
 * source roots, so without this provider every target directory would look like a module and not
 * like the sources it holds. Registered first, to answer before it; it answers only for source
 * roots of SwiftPM modules and leaves every other directory to the platform.
 */
class SwiftTargetDirectoryIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val directory = element as? PsiDirectory ?: return null
        val root = sourceFolderOf(directory)?.jpsElement?.asTyped() ?: return null
        return rootIcon(root)
    }

    private fun sourceFolderOf(directory: PsiDirectory): SourceFolder? {
        val file = directory.virtualFile
        val module = ProjectFileIndex.getInstance(directory.project).getModuleForFile(file, false) ?: return null
        if (module.isDisposed || ModuleType.get(module).id != SwiftPackageModuleType.ID) return null
        return ModuleRootManager.getInstance(module).contentEntries
            .flatMap { it.sourceFolders.asList() }
            .firstOrNull { it.file == file }
    }

    /** The icon the root's type gives its roots, as the Project Structure dialog shows them. */
    private fun <P : JpsElement> rootIcon(root: JpsTypedModuleSourceRoot<P>): Icon? =
        ModuleSourceRootEditHandler.getEditHandler(root.rootType)?.getRootIcon(root.properties)
}
