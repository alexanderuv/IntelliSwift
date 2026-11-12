package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPackageModuleType
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmProjectModel
import java.nio.file.Files
import java.nio.file.Path

/** What one `swift build` builds: the package in [packageDir], or only its [target]. */
data class SwiftBuildScope(val packageDir: Path, val target: String?) {
    val packageName: String get() = packageDir.fileName?.toString() ?: packageDir.toString()
}

/**
 * Maps IntelliJ modules and files to the SwiftPM package and target they belong to. This is the
 * only place the build integration makes that mapping.
 *
 * For modules imported by the SwiftPM project import, the package and target come from the
 * imported project data ([SwiftPmProjectModel]), so targets with a custom `path:` are built on
 * their own. Before a package is linked, or for modules no build system imported, it falls back
 * to the file system:
 * - the package of a file is the nearest directory, the file's own or above, with a `Package.swift`;
 * - a file under `<package>/Sources/<T>/` or `<package>/Tests/<T>/` (SwiftPM's standard layout)
 *   belongs to target `<T>`; anything else in the package builds the whole package;
 * - a module is located by its first content root that is a package file ([isPackageFile]): the
 *   package directory, or a directory of SwiftPM's layout in it. A module elsewhere below a
 *   `Package.swift` is left to the other builds.
 */
object SwiftPackageLocator {
    /** Directories whose subdirectories are targets that `swift build --target` builds on their own. */
    private val TARGET_DIRECTORIES = setOf("Sources", "Tests")

    /** Directories SwiftPM's layout puts targets in. */
    private val LAYOUT_DIRECTORIES = TARGET_DIRECTORIES + "Plugins"

    /**
     * Whether `swift build` builds [module]: it is a SwiftPM module, or a module that no other build
     * system owns with a content root that is a package file ([isPackageFile]).
     */
    fun isSwiftModule(module: Module): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        if (module.isDisposed) return@computeBlocking false
        if (ModuleType.get(module).id == SwiftPackageModuleType.ID) return@computeBlocking true
        !ownedByAnotherBuildSystem(module) && contentRootScope(module) != null
    }

    /** Whether an External System other than SwiftPM (Gradle, Maven, ...) imported [module]; its builds are not ours. */
    fun isOwnedByAnotherBuildSystem(module: Module): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        !module.isDisposed && ownedByAnotherBuildSystem(module)
    }

    private fun ownedByAnotherBuildSystem(module: Module): Boolean {
        val owner = ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId()
        return owner != null && owner != SwiftPm.SYSTEM_ID.id
    }

    /** The package (and target, for a module of one target) [module] builds, or `null` if it is not in a package. */
    fun scopeOf(module: Module): SwiftBuildScope? = ReadAction.computeBlocking<SwiftBuildScope?, RuntimeException> {
        if (module.isDisposed) null else importedScope(module) ?: contentRootScope(module)
    }

    /** The package and target [file] belongs to, or `null` if it is not in a SwiftPM package. */
    fun scopeOf(project: Project, file: VirtualFile): SwiftBuildScope? = ReadAction.computeBlocking<SwiftBuildScope?, RuntimeException> {
        val target = SwiftPmProjectModel.targetFor(project, file)
        if (target != null) return@computeBlocking SwiftBuildScope(Path.of(target.packagePath).normalize(), target.name)
        val pkg = SwiftPmProjectModel.packageFor(project, file)
        if (pkg != null) return@computeBlocking SwiftBuildScope(Path.of(pkg.packagePath).normalize(), null)
        layoutScope(file)
    }

    /**
     * The linked root package whose `swift build` also builds [packageDir], when [packageDir] is an
     * editable (`path:`) dependency in that root's graph; `null` for roots and unknown packages.
     */
    fun buildingRoot(project: Project, packageDir: Path): Path? {
        val dir = packageDir.normalize()
        for (linked in SwiftPmProjectModel.linkedPackagePaths(project)) {
            val root = Path.of(linked).normalize()
            if (root == dir) return null
            if (dir in SwiftPmProjectModel.editablePackageDirectories(project, linked)) return root
        }
        return null
    }

    /** Whether [scope] builds a test target, as the imported project or else SwiftPM's layout says. */
    fun isTestTarget(project: Project, scope: SwiftBuildScope): Boolean {
        val target = scope.target ?: return false
        val module = SwiftPmProjectModel.moduleForTarget(project, scope.packageDir.toString(), target)
        return module?.let { SwiftPmProjectModel.targetOf(it)?.kind?.isTest } ?: Files.isDirectory(scope.packageDir.resolve("Tests").resolve(target))
    }

    /** The nearest directory containing a `Package.swift`: [file] itself if it is one, or one above it. */
    fun packageDirOf(file: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            if (dir.findChild(SwiftPackage.MANIFEST)?.isDirectory == false) return dir
            dir = dir.parent
        }
        return null
    }

    private fun importedScope(module: Module): SwiftBuildScope? {
        val pkg = SwiftPmProjectModel.packageOf(module) ?: return null
        return SwiftBuildScope(Path.of(pkg.packagePath).normalize(), SwiftPmProjectModel.targetOf(module)?.name)
    }

    /**
     * Whether SwiftPM's layout makes [file] part of a package's build, for files of modules that
     * no build system imported: the package directory, its manifest, or anything under its
     * `Sources`, `Tests` or `Plugins` directory. Other files below a `Package.swift`, such as an
     * Android or Java module kept in the same repository, may belong to another build.
     */
    fun isPackageFile(file: VirtualFile): Boolean = ownedLayoutScope(file) != null

    /** The scope by SwiftPM's standard directory layout; see the class documentation. */
    private fun layoutScope(file: VirtualFile): SwiftBuildScope? = layout(file)?.first

    private fun ownedLayoutScope(file: VirtualFile): SwiftBuildScope? {
        val (scope, segments) = layout(file) ?: return null
        val owned = segments.isEmpty() || segments == listOf(SwiftPackage.MANIFEST) || segments.size >= 2 && segments[0] in LAYOUT_DIRECTORIES
        return scope.takeIf { owned }
    }

    /** The layout scope of [file], with its path segments relative to its package directory. */
    private fun layout(file: VirtualFile): Pair<SwiftBuildScope, List<String>>? {
        val packageDir = packageDirOf(file) ?: return null
        val packagePath = packageDir.fileSystem.getNioPath(packageDir) ?: return null
        val segments = VfsUtilCore.getRelativePath(file, packageDir, '/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        val target = segments.takeIf { it.size >= 2 && it[0] in TARGET_DIRECTORIES }?.get(1)
        return SwiftBuildScope(packagePath, target) to segments
    }

    /** A module that no build system imported is a Swift module when a content root is a package file. */
    private fun contentRootScope(module: Module): SwiftBuildScope? =
        ModuleRootManager.getInstance(module).contentRoots.firstNotNullOfOrNull(::ownedLayoutScope)
}
