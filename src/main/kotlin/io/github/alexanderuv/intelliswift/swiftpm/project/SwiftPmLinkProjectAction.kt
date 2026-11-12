package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * *Link SwiftPM Project*: links a package and loads it. On a `Package.swift`, or a directory
 * holding one, in the Project view or editor it links that package; elsewhere, such as the
 * SwiftPM tool window toolbar or *Find Action*, it asks for the package first.
 */
class SwiftPmLinkProjectAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val toolWindowSystem = e.getData(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID)
        e.presentation.isEnabledAndVisible = when {
            project == null -> false
            // The External System toolbars are shared by every build tool's tool window.
            toolWindowSystem != null -> toolWindowSystem == SwiftPm.SYSTEM_ID
            e.isFromContextMenu -> contextPackage(e)?.let { !isLinked(project, it) } == true
            else -> true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val chosen = contextPackage(e)?.takeUnless { isLinked(project, it) }
            ?: FileChooser.chooseFile(SwiftPmManager.packageChooserDescriptor(), project, project.guessProjectDir())
            ?: return
        // Linking first unlinks other build systems linked at the same path, so it must only
        // start for a real package: the chooser lets any directory be picked.
        val packageDir = SwiftPmPaths.packageDirectory(chosen)
        if (packageDir == null) {
            Messages.showErrorDialog(project, "${chosen.presentableUrl} contains no ${SwiftPackage.MANIFEST}.", "Link SwiftPM Project")
            return
        }
        if (isLinked(project, packageDir)) {
            Messages.showInfoMessage(project, "${packageDir.presentableUrl} is already linked, or is part of a linked package.", "Link SwiftPM Project")
            return
        }
        project.service<SwiftPmCoroutineScope>().scope.launch {
            SwiftPmOpenProjectProvider().linkToExistingProjectAsync(packageDir, project)
        }
    }

    /**
     * The package directory the context file stands for. Only packages on the local file system
     * can be linked: SwiftPM runs on directories, not on files inside archives or remote ones.
     */
    private fun contextPackage(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }?.let(SwiftPmPaths::packageDirectory)

    /**
     * Whether [directory] is a linked package or one of the packages a linked package imported (a
     * `path:` dependency): the platform refuses to link those again.
     */
    private fun isLinked(project: Project, directory: VirtualFile): Boolean =
        SwiftPmSettings.getInstance(project).getLinkedProjectSettings(SwiftPmPaths.systemIndependent(directory.toNioPath())) != null
}

/** The coroutine scope SwiftPM actions launch their work in; cancelled when the project closes. */
@Service(Service.Level.PROJECT)
class SwiftPmCoroutineScope(val scope: CoroutineScope)
