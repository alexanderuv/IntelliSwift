package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.ide.impl.toOpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmLocalSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmProjectSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import javax.swing.Icon

/**
 * Links a SwiftPM package to an IDE project and loads it, after the user has confirmed trusting
 * it: loading runs the package's manifest. Used by *File | Open*, the "Load SwiftPM project"
 * notification and the *Link SwiftPM Project* action.
 */
class SwiftPmOpenProjectProvider : AbstractOpenProjectProvider() {
    override val systemId: ProjectSystemId = SwiftPm.SYSTEM_ID

    override fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.name == SwiftPackage.MANIFEST

    override suspend fun linkProject(projectFile: VirtualFile, project: Project) {
        val directory = SwiftPmPaths.packageDirectory(projectFile) ?: return
        val path = directory.toNioPath()
        if (!ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync(project, systemId, path)) return
        val settings = SwiftPmProjectSettings(SwiftPmPaths.systemIndependent(path)).apply { setupNewProjectDefault() }
        ExternalSystemUtil.linkExternalProject(settings, ImportSpecBuilder(project, systemId))
    }

    /**
     * Unlinks the package at [externalProjectPath]: forgets its settings and imported data, and
     * removes its modules and the libraries no other module uses. This is what the External System
     * view's *Unlink* does; its implementation is internal API, hence this public-API version.
     *
     * A package's modules can belong to two linked roots at once: a `path:` package two roots
     * depend on, or one that is also linked itself. The IDE keeps one module for both, owned by
     * whichever root was imported last. Such modules stay, and the roots that still use them are
     * reloaded, so they own them again.
     */
    suspend fun unlinkProject(project: Project, externalProjectPath: String) {
        SwiftPmLocalSettings.getInstance(project).forgetExternalProjects(setOf(externalProjectPath))
        ExternalProjectsManagerImpl.getInstance(project).forgetExternalProjectData(systemId, externalProjectPath)
        SwiftPmSettings.getInstance(project).unlinkExternalProject(externalProjectPath)
        val stillUsing = edtWriteAction { removeModules(project, externalProjectPath) }
        for (root in stillUsing) ExternalSystemUtil.refreshProject(root, ImportSpecBuilder(project, systemId))
    }

    /** Removes the modules of [externalProjectPath] no other linked root has; returns the roots that have the others. */
    private fun removeModules(project: Project, externalProjectPath: String): Set<String> {
        val moduleManager = ModuleManager.getInstance(project)
        val owned = moduleManager.modules.filter {
            ExternalSystemApiUtil.isExternalSystemAwareModule(systemId, it) && ExternalSystemApiUtil.getExternalRootProjectPath(it) == externalProjectPath
        }
        if (owned.isEmpty()) return emptySet()
        // A module of another linked root's imported data -> that root. A module is the same one
        // when both its id and its package directory match: ids are SwiftPM package identities,
        // the directory name, so different packages in different roots may share one.
        val elsewhere = HashMap<Pair<String, String>, String>()
        for (root in SwiftPmProjectModel.linkedPackagePaths(project)) {
            val node = SwiftPmProjectModel.projectNode(project, root) ?: continue
            ExternalSystemApiUtil.findAllRecursively(node, ProjectKeys.MODULE).forEach {
                elsewhere.putIfAbsent(it.data.id to it.data.linkedExternalProjectPath, root)
            }
        }
        fun rootStillUsing(module: Module): String? {
            val id = ExternalSystemApiUtil.getExternalProjectId(module) ?: return null
            val path = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return null
            return elsewhere[id to path]
        }
        val (kept, removed) = owned.partition { rootStillUsing(it) != null }
        if (removed.isNotEmpty()) {
            moduleManager.getModifiableModel().apply {
                removed.forEach(::disposeModule)
                commit()
            }
        }
        removeOrphanLibraries(project, moduleManager)
        return kept.mapNotNull(::rootStillUsing).toSet()
    }

    private fun removeOrphanLibraries(project: Project, moduleManager: ModuleManager) {
        val used = HashSet<Library>()
        for (module in moduleManager.modules) {
            OrderEnumerator.orderEntries(module).forEachLibrary { used += it; true }
        }
        val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val orphans = table.libraries.filter { ExternalSystemApiUtil.isExternalSystemLibrary(it, systemId) && it !in used }
        if (orphans.isEmpty()) return
        table.modifiableModel.apply {
            orphans.forEach(::removeLibrary)
            commit()
        }
    }
}

/** *File | Open* on a `Package.swift`, or on a directory that contains one, imports the package. */
class SwiftPmProjectOpenProcessor : ProjectOpenProcessor() {
    private val provider = SwiftPmOpenProjectProvider()

    override val name: String = SwiftPm.SYSTEM_ID.readableName

    override val icon: Icon = SwiftIcons.FILE

    override fun canOpenProject(file: VirtualFile): Boolean = provider.canOpenProject(file)

    override suspend fun openProjectAsync(virtualFile: VirtualFile, projectOpenOptions: ProjectOpenOptions): Project? =
        provider.openProject(virtualFile, projectOpenOptions.toOpenProjectTask())

    override fun canImportProjectAfterwards(): Boolean = true

    override suspend fun importProjectAfterwardsAsync(project: Project, file: VirtualFile) {
        provider.linkToExistingProjectAsync(file, project)
    }
}

/**
 * Detects `Package.swift` files of packages that are not linked, for the platform's
 * "SwiftPM build script found — Load" notification, and links them when the user agrees.
 */
class SwiftPmUnlinkedProjectAware : ExternalSystemUnlinkedProjectAware {
    override val systemId: ProjectSystemId = SwiftPm.SYSTEM_ID

    override fun buildFileExtensions(): Array<String> = arrayOf("swift")

    override fun isBuildFile(project: Project, buildFile: VirtualFile): Boolean = buildFile.name == SwiftPackage.MANIFEST

    override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean =
        SwiftPmSettings.getInstance(project).getLinkedProjectSettings(externalProjectPath) != null

    override fun subscribe(project: Project, listener: ExternalSystemProjectLinkListener, parentDisposable: Disposable) {
        SwiftPmSettings.getInstance(project).subscribe(object : ExternalSystemSettingsListener<SwiftPmProjectSettings> {
            override fun onProjectsLinked(settings: Collection<SwiftPmProjectSettings>) =
                settings.forEach { listener.onProjectLinked(it.externalProjectPath) }

            override fun onProjectsUnlinked(linkedProjectPaths: Set<String>) = linkedProjectPaths.forEach(listener::onProjectUnlinked)
        }, parentDisposable)
    }

    override suspend fun linkAndLoadProjectAsync(project: Project, externalProjectPath: String) {
        SwiftPmOpenProjectProvider().linkToExistingProjectAsync(externalProjectPath, project)
    }

    override suspend fun unlinkProject(project: Project, externalProjectPath: String) {
        SwiftPmOpenProjectProvider().unlinkProject(project, externalProjectPath)
    }
}
