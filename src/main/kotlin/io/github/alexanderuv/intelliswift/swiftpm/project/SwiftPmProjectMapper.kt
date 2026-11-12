package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.LibraryData
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.model.project.LibraryLevel
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ModuleDependencyData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.io.FileUtil
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraph
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProduct
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTarget
import java.nio.file.Path

/**
 * Maps a [SwiftPackageGraph] to the External System project model, as ADR 0002 describes:
 *
 * - every editable package (the root, `path:` dependencies, packages in edit mode) is a module
 *   whose content root is the package directory, with `.build` and `.swiftpm` excluded;
 * - every target of an editable package is a module nested under its package module, with the
 *   target directory as content root and as source root (test source root for test targets).
 *   Binary and system-library targets have no sources of their own and get no roots;
 * - target dependencies are module dependencies; a product dependency is a dependency on the
 *   product's target modules when the product's package is editable, and on a project library
 *   when it is a checkout;
 * - every product of a checkout package is a library whose source roots are the directories of
 *   the targets in the product.
 *
 * All dependencies are exported: Swift lets a module import the modules of its transitive
 * dependencies, and exporting gives the IDE the same visibility. Libraries cannot depend on
 * libraries, so a module that depends on a checkout product also gets the libraries that product
 * needs, transitively.
 */
object SwiftPmProjectMapper {

    fun map(graph: SwiftPackageGraph): DataNode<ProjectData> = Mapping(graph).run()

    /**
     * The model for a package that may not be evaluated: preview mode, used for projects that are
     * not trusted, since loading a package runs its manifest. Just the root module and its content.
     */
    fun preview(root: Path): DataNode<ProjectData> {
        val rootPath = path(root)
        val name = root.fileName?.toString() ?: rootPath
        val project = DataNode(ProjectKeys.PROJECT, ProjectData(SwiftPm.SYSTEM_ID, name, rootPath, rootPath), null)
        // SwiftPM's identity for a root package is its lowercased directory name.
        val module = project.createChild(ProjectKeys.MODULE, moduleData(packageModuleId(name.lowercase()), name, rootPath, rootPath))
        module.createChild(ProjectKeys.CONTENT_ROOT, contentRoot(rootPath, excludes = true))
        return project
    }

    fun packageModuleId(identity: String): String = identity

    fun targetModuleId(packageIdentity: String, target: String): String = "$packageIdentity:$target"

    fun libraryName(packageIdentity: String, product: String): String = "$packageIdentity:$product"

    private class Mapping(private val graph: SwiftPackageGraph) {
        private val rootPath = path(graph.root.path)
        private val project = DataNode(ProjectKeys.PROJECT, ProjectData(SwiftPm.SYSTEM_ID, graph.root.name, rootPath, rootPath), null)
        private val editable = graph.packages.values.filter(graph::isEditable)
        private val targetModules = HashMap<Pair<String, String>, DataNode<ModuleData>>()
        private val libraries = HashMap<Pair<String, String>, LibraryData>()
        /** Module names given so far, lowercased. */
        private val usedModuleNames = HashSet<String>()

        fun run(): DataNode<ProjectData> {
            graph.dependencies.filterNot(graph::isEditable).forEach(::addCheckout)
            // Ordered so the root package comes first.
            (listOf(graph.root) + editable.filter { it.identity != graph.root.identity }).forEach(::addPackageModule)
            for (pkg in editable) {
                for (target in pkg.targets) addDependencies(pkg, target)
            }
            return project
        }

        private fun addCheckout(pkg: SwiftPackage) {
            project.createChild(SwiftPackageData.KEY, packageData(pkg))
            for (product in pkg.products) {
                val library = LibraryData(SwiftPm.SYSTEM_ID, libraryName(pkg.identity, product.name)).apply {
                    setGroup(pkg.identity)
                    artifactId = product.name
                    version = versionOf(pkg)
                    for (target in productTargets(pkg, product)) {
                        if (target.kind.hasSources) addPath(LibraryPathType.SOURCE, path(target.path))
                    }
                }
                project.createChild(ProjectKeys.LIBRARY, library)
                libraries[pkg.identity to product.name] = library
            }
        }

        private fun addPackageModule(pkg: SwiftPackage) {
            val packagePath = path(pkg.path)
            val name = uniqueModuleName(pkg.name, pkg.identity)
            val module = project.createChild(ProjectKeys.MODULE, moduleData(packageModuleId(pkg.identity), name, packagePath, rootPath))
            module.createChild(SwiftPackageData.KEY, packageData(pkg))
            for (product in pkg.products) {
                module.createChild(SwiftProductData.KEY, SwiftProductData(pkg.identity, packagePath, product.name, product.kind, product.targets))
            }
            // A target located at the package directory takes over the package's content root:
            // two modules cannot share one.
            val targetAtRoot = pkg.targets.any { it.kind.hasSources && it.path == pkg.path }
            if (!targetAtRoot) module.createChild(ProjectKeys.CONTENT_ROOT, contentRoot(packagePath, excludes = true))
            addPackageTasks(module, pkg, packagePath)

            for (target in pkg.targets) {
                val targetModule = module.createChild(
                    ProjectKeys.MODULE,
                    moduleData(targetModuleId(pkg.identity, target.name), uniqueModuleName("$name.${target.name}", "${pkg.identity}.${target.name}"), packagePath, rootPath),
                )
                targetModules[pkg.identity to target.name] = targetModule
                targetModule.createChild(SwiftTargetData.KEY, targetData(pkg, target))
                if (target.kind.hasSources) {
                    val targetPath = path(target.path)
                    val root = contentRoot(targetPath, excludes = target.path == pkg.path)
                    root.storePath(if (target.kind.isTest) ExternalSystemSourceType.TEST else ExternalSystemSourceType.SOURCE, targetPath)
                    targetModule.createChild(ProjectKeys.CONTENT_ROOT, root)
                }
                targetModule.createChild(ProjectKeys.TASK, task(SwiftPmTask.BuildTarget(target.name), packagePath, SwiftPmTask.GROUP_BUILD,
                    "Builds target ${target.name}"))
            }
        }

        private fun addPackageTasks(module: DataNode<ModuleData>, pkg: SwiftPackage, packagePath: String) {
            module.createChild(ProjectKeys.TASK, task(SwiftPmTask.Build, packagePath, SwiftPmTask.GROUP_BUILD, "Builds all targets of ${pkg.name}"))
            module.createChild(ProjectKeys.TASK, task(SwiftPmTask.Clean, packagePath, SwiftPmTask.GROUP_BUILD, "Deletes build artifacts of ${pkg.name}"))
            if (pkg.targets.any { it.kind.isTest }) {
                val test = task(SwiftPmTask.Test, packagePath, SwiftPmTask.GROUP_VERIFICATION, "Builds and runs the tests of ${pkg.name}")
                test.isTest = true
                module.createChild(ProjectKeys.TASK, test)
            }
            module.createChild(ProjectKeys.TASK, task(SwiftPmTask.Resolve, packagePath, SwiftPmTask.GROUP_DEPENDENCIES,
                "Resolves the dependencies of ${pkg.name}"))
            module.createChild(ProjectKeys.TASK, task(SwiftPmTask.Update, packagePath, SwiftPmTask.GROUP_DEPENDENCIES,
                "Updates the dependencies of ${pkg.name} to their latest allowed versions"))
            for (product in pkg.products.filter { it.kind == SwiftProductKind.EXECUTABLE }) {
                module.createChild(ProjectKeys.TASK, task(SwiftPmTask.Run(product.name), packagePath, SwiftPmTask.GROUP_RUN,
                    "Builds and runs ${product.name}"))
            }
        }

        private fun addDependencies(pkg: SwiftPackage, target: SwiftTarget) {
            val owner = targetModules.getValue(pkg.identity to target.name)
            val modules = LinkedHashSet<DataNode<ModuleData>>()
            val libs = LinkedHashSet<LibraryData>()
            for (name in target.targetDependencies) {
                targetModules[pkg.identity to name]?.let(modules::add)
            }
            for (dependency in target.productDependencies) {
                val (depPackage, product) = graph.resolveProduct(pkg, dependency) ?: continue
                collectProduct(depPackage, product, modules, libs, HashSet())
            }
            modules.remove(owner)
            for (module in modules) {
                owner.createChild(ProjectKeys.MODULE_DEPENDENCY, ModuleDependencyData(owner.data, module.data).apply {
                    scope = DependencyScope.COMPILE
                    isExported = true
                })
            }
            for (library in libs) {
                owner.createChild(ProjectKeys.LIBRARY_DEPENDENCY, LibraryDependencyData(owner.data, library, LibraryLevel.PROJECT).apply {
                    scope = DependencyScope.COMPILE
                    isExported = true
                })
            }
        }

        /**
         * What depending on [product] of [pkg] brings in: its target modules for an editable
         * package; for a checkout, its library and those of the products its targets depend on.
         */
        private fun collectProduct(
            pkg: SwiftPackage,
            product: SwiftProduct,
            modules: MutableSet<DataNode<ModuleData>>,
            libs: MutableSet<LibraryData>,
            visited: MutableSet<Pair<String, String>>,
        ) {
            if (!visited.add(pkg.identity to product.name)) return
            if (graph.isEditable(pkg)) {
                product.targets.mapNotNullTo(modules) { targetModules[pkg.identity to it] }
                return
            }
            libraries[pkg.identity to product.name]?.let(libs::add)
            for (target in productTargets(pkg, product)) {
                for (dependency in target.productDependencies) {
                    val (depPackage, depProduct) = graph.resolveProduct(pkg, dependency) ?: continue
                    collectProduct(depPackage, depProduct, modules, libs, visited)
                }
            }
        }

        /**
         * [preferred], else [fallback], else [fallback] with a number: the first not yet taken.
         * Names are compared ignoring case, since each module's `.iml` file is named after it and
         * file systems may ignore case.
         */
        private fun uniqueModuleName(preferred: String, fallback: String): String {
            val name = (sequenceOf(preferred, fallback) + generateSequence(2) { it + 1 }.map { "$fallback.$it" })
                .first { candidate -> candidate.lowercase() !in usedModuleNames }
            usedModuleNames += name.lowercase()
            return name
        }

        private fun versionOf(pkg: SwiftPackage): String = graph.resolvedNode(pkg)?.version ?: "unspecified"

        private fun packageData(pkg: SwiftPackage) =
            SwiftPackageData(pkg.identity, pkg.name, path(pkg.path), graph.isEditable(pkg), versionOf(pkg), pkg.toolsVersion)
    }

    /**
     * The targets a product is built from: those SwiftPM lists as members of the product, which
     * includes the targets the product's own targets depend on.
     */
    internal fun productTargets(pkg: SwiftPackage, product: SwiftProduct): List<SwiftTarget> =
        pkg.targets.filter { it.name in product.targets || product.name in it.productMemberships }

    private fun targetData(pkg: SwiftPackage, target: SwiftTarget) = SwiftTargetData(
        packageIdentity = pkg.identity,
        packagePath = path(pkg.path),
        name = target.name,
        moduleName = target.moduleName,
        kind = target.kind,
        targetPath = path(target.path),
        sources = target.sources,
        productMemberships = target.productMemberships,
    )

    private fun moduleData(id: String, name: String, packagePath: String, rootPath: String): ModuleData =
        ModuleData(id, SwiftPm.SYSTEM_ID, SwiftPackageModuleType.ID, name, "$rootPath/.idea/modules", packagePath)

    private fun contentRoot(path: String, excludes: Boolean): ContentRootData = ContentRootData(SwiftPm.SYSTEM_ID, path).apply {
        if (excludes) SwiftPm.EXCLUDED_DIRECTORY_NAMES.forEach { storePath(ExternalSystemSourceType.EXCLUDED, "$path/$it") }
    }

    private fun task(task: SwiftPmTask, packagePath: String, group: String, description: String): TaskData =
        TaskData(SwiftPm.SYSTEM_ID, task.name, packagePath, description).apply { this.group = group }

    private fun path(path: Path): String = FileUtil.toSystemIndependentName(path.toAbsolutePath().normalize().toString())
}
