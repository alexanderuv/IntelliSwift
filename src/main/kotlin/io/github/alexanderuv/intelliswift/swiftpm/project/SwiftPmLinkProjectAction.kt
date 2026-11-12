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
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

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
        val file = contextPackage(e)?.takeUnless { isLinked(project, it) }
            ?: FileChooser.chooseFile(SwiftPmManager.packageChooserDescriptor(), project, project.guessProjectDir())
            ?: return
        project.service<SwiftPmCoroutineScope>().scope.launch {
            SwiftPmOpenProjectProvider().linkToExistingProjectAsync(file, project)
        }
    }

    /** The package directory the context file stands for. */
    private fun contextPackage(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(SwiftPmPaths::packageDirectory)

    private fun isLinked(project: Project, directory: VirtualFile): Boolean {
        val path = directory.toNioPath().normalize()
        return SwiftPmProjectModel.linkedPackagePaths(project).any { Path.of(it).normalize() == path }
    }
}

/** The coroutine scope SwiftPM actions launch their work in; cancelled when the project closes. */
@Service(Service.Level.PROJECT)
class SwiftPmCoroutineScope(val scope: CoroutineScope)
