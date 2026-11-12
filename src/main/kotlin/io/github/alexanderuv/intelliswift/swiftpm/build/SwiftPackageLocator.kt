package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** What one `swift build` builds: the package in [packageDir], or only its [target]. */
data class SwiftBuildScope(val packageDir: Path, val target: String?) {
    val packageName: String get() = packageDir.fileName?.toString() ?: packageDir.toString()
}

/**
 * Maps IntelliJ modules and files to the SwiftPM package and target they belong to. This is the
 * only place the build integration makes that mapping.
 *
 * It works from the file system, so it does not depend on the SwiftPM project import:
 * - the package of a file is the nearest directory, the file's own or above, with a `Package.swift`;
 * - a file under `<package>/Sources/<T>/` or `<package>/Tests/<T>/` (SwiftPM's standard layout)
 *   belongs to target `<T>`; anything else in the package, such as the package directory itself,
 *   builds the whole package;
 * - a module is located by its first content root that lies in a package.
 *
 * Targets with a custom `path:` are therefore built as part of the whole package. Once the
 * imported project data is available, [scopeOf] for modules should read the module's package and
 * target from it instead.
 */
object SwiftPackageLocator {
    /** The module type of modules created by the SwiftPM project import. */
    const val SWIFT_MODULE_TYPE_ID: String = "SWIFT_MODULE"

    private const val MANIFEST = "Package.swift"
    private const val SWIFTPM_SYSTEM_ID = "SWIFTPM"
    private val TARGET_DIRECTORIES = setOf("Sources", "Tests")

    /**
     * Whether `swift build` builds [module]: it is a SwiftPM module, or a module that no other build
     * system owns with a content root inside a SwiftPM package.
     */
    fun isSwiftModule(module: Module): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        if (module.isDisposed) return@computeBlocking false
        if (ModuleType.get(module).id == SWIFT_MODULE_TYPE_ID) return@computeBlocking true
        !ownedByAnotherBuildSystem(module) && contentRootScope(module) != null
    }

    /** Whether an External System other than SwiftPM (Gradle, Maven, ...) imported [module]; its builds are not ours. */
    fun isOwnedByAnotherBuildSystem(module: Module): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        !module.isDisposed && ownedByAnotherBuildSystem(module)
    }

    private fun ownedByAnotherBuildSystem(module: Module): Boolean {
        val owner = ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId()
        return owner != null && owner != SWIFTPM_SYSTEM_ID
    }

    /** The package (and target, for a module of one target) [module] builds, or `null` if it is not in a package. */
    fun scopeOf(module: Module): SwiftBuildScope? = ReadAction.computeBlocking<SwiftBuildScope?, RuntimeException> {
        if (module.isDisposed) null else contentRootScope(module)
    }

    /** The package and target [file] belongs to, or `null` if it is not in a SwiftPM package. */
    fun scopeOf(file: VirtualFile): SwiftBuildScope? {
        val packageDir = packageDirOf(file) ?: return null
        val packagePath = packageDir.fileSystem.getNioPath(packageDir) ?: return null
        val segments = VfsUtilCore.getRelativePath(file, packageDir, '/')?.split('/').orEmpty()
        val target = segments.takeIf { it.size >= 2 && it[0] in TARGET_DIRECTORIES && it[1].isNotEmpty() }?.get(1)
        return SwiftBuildScope(packagePath, target)
    }

    /** The nearest directory containing a `Package.swift`: [file] itself if it is one, or one above it. */
    fun packageDirOf(file: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            if (dir.findChild(MANIFEST)?.isDirectory == false) return dir
            dir = dir.parent
        }
        return null
    }

    private fun contentRootScope(module: Module): SwiftBuildScope? =
        ModuleRootManager.getInstance(module).contentRoots.firstNotNullOfOrNull(::scopeOf)
}
