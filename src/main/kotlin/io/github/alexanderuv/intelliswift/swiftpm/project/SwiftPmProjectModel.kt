package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import java.nio.file.Path

/**
 * Reads the imported SwiftPM model back: which package, target and IDE module a file or module
 * belongs to. Build, run and test features use this rather than walking the data nodes
 * themselves; see [SwiftPackageData] for the shape of the tree.
 *
 * Everything here reads the External System's cached project data, so it reflects the last
 * successful sync and returns `null` for projects that were never loaded.
 */
object SwiftPmProjectModel {

    /** The external project paths (root package directories) of the linked SwiftPM packages. */
    fun linkedPackagePaths(project: Project): List<String> =
        SwiftPmSettings.getInstance(project).linkedProjectsSettings.map { it.externalProjectPath }

    /** The imported structure of the package linked at [linkedPath], if it was loaded. */
    fun projectNode(project: Project, linkedPath: String): DataNode<ProjectData>? =
        ExternalSystemApiUtil.findProjectNode(project, SwiftPm.SYSTEM_ID, linkedPath)

    /** All packages of all linked graphs: editable ones and checkouts. */
    fun packages(project: Project): List<SwiftPackageData> =
        linkedPackagePaths(project).flatMap { path ->
            projectNode(project, path)?.let { ExternalSystemApiUtil.findAllRecursively(it, SwiftPackageData.KEY).map(DataNode<SwiftPackageData>::getData) }.orEmpty()
        }

    /**
     * The directories whose `Package.swift` and `Package.resolved` affect the package linked at
     * [linkedPath]: the root and every editable package of its graph.
     */
    fun editablePackageDirectories(project: Project, linkedPath: String): List<Path> {
        val root = Path.of(linkedPath).normalize()
        val editable = projectNode(project, linkedPath)
            ?.let { ExternalSystemApiUtil.findAllRecursively(it, SwiftPackageData.KEY) }
            .orEmpty()
            .map { it.data }
            .filter { it.editable }
            .map { Path.of(it.packagePath).normalize() }
        return (listOf(root) + editable).distinct()
    }

    /** The External System data node [module] was imported from, if it is a SwiftPM module. */
    fun moduleNode(module: Module): DataNode<ModuleData>? {
        if (!ExternalSystemApiUtil.isExternalSystemAwareModule(SwiftPm.SYSTEM_ID, module)) return null
        val id = ExternalSystemApiUtil.getExternalProjectId(module) ?: return null
        val rootPath = ExternalSystemApiUtil.getExternalRootProjectPath(module) ?: return null
        val projectNode = projectNode(module.project, rootPath) ?: return null
        @Suppress("UNCHECKED_CAST")
        return ExternalSystemApiUtil.findFirstRecursively(projectNode) {
            it.key == ProjectKeys.MODULE && (it.data as ModuleData).id == id
        } as DataNode<ModuleData>?
    }

    /** The target [module] was imported from; `null` for a package module or a non-SwiftPM module. */
    fun targetOf(module: Module): SwiftTargetData? = moduleNode(module)?.let { ExternalSystemApiUtil.find(it, SwiftTargetData.KEY)?.data }

    /** The package [module] belongs to: the package of a package module, or of a target module. */
    fun packageOf(module: Module): SwiftPackageData? {
        var node: DataNode<*>? = moduleNode(module)
        while (node != null && node.key == ProjectKeys.MODULE) {
            ExternalSystemApiUtil.find(node, SwiftPackageData.KEY)?.let { return it.data }
            node = node.parent
        }
        return null
    }

    /** The module [file] belongs to, if it is a SwiftPM module: a target module for sources. */
    fun moduleFor(project: Project, file: VirtualFile): Module? =
        ProjectFileIndex.getInstance(project).getModuleForFile(file)?.takeIf { ExternalSystemApiUtil.isExternalSystemAwareModule(SwiftPm.SYSTEM_ID, it) }

    /** The target whose sources contain [file]. */
    fun targetFor(project: Project, file: VirtualFile): SwiftTargetData? = moduleFor(project, file)?.let(::targetOf)

    /** The package containing [file], also for files outside any target (such as `Package.swift`). */
    fun packageFor(project: Project, file: VirtualFile): SwiftPackageData? = moduleFor(project, file)?.let(::packageOf)

    /** The products of the package that [module] belongs to (a package module or one of its target modules). */
    fun productsOf(module: Module): List<SwiftProductData> {
        var node: DataNode<*>? = moduleNode(module)
        while (node != null && node.key == ProjectKeys.MODULE) {
            if (ExternalSystemApiUtil.find(node, SwiftPackageData.KEY) != null) {
                return ExternalSystemApiUtil.findAll(node, SwiftProductData.KEY).map { it.data }
            }
            node = node.parent
        }
        return emptyList()
    }

    /** The IDE module of [target] of the package at [packagePath], once imported. */
    fun moduleForTarget(project: Project, packagePath: String, target: String): Module? =
        ModuleManager.getInstance(project).modules.firstOrNull { module ->
            targetOf(module)?.let { it.name == target && Path.of(it.packagePath) == Path.of(packagePath) } == true
        }
}
