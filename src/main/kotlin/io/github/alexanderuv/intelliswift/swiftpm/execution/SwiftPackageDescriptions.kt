package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.ide.trustedProjects.TrustedProjectsLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandRunner
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageJson
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmPathMapping
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPmProjectModel
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The SwiftPM target a source file belongs to. */
data class SwiftTargetInfo(
    val packageDir: VirtualFile,
    val targetName: String,
    /** The module name, used in test identifiers and filters. */
    val moduleName: String,
    /** The target kind, or null if the package is not described yet and the directory layout does not tell. */
    val kind: SwiftTargetKind?,
    /** Executable products built from this target. */
    val executableProducts: List<String>,
) {
    val isTest: Boolean get() = kind == SwiftTargetKind.TEST
}

/**
 * The output of `swift package describe` for the packages the run configurations and gutter
 * markers look at, cached per package directory and reloaded when its `Package.swift` changes.
 *
 * For packages the SwiftPM project import has loaded, [targetOf] answers from the imported project
 * data instead. Lookups never wait for SwiftPM: a missing or stale description is loaded in the
 * background (and the editor markers are refreshed when it arrives), and meanwhile the target is
 * inferred from SwiftPM's standard layout (`Sources/<Target>`, `Tests/<Target>`). That layout is
 * all there is for packages that are not trusted, since describing one runs its manifest.
 */
@Service(Service.Level.PROJECT)
class SwiftPackageDescriptions(private val project: Project) : Disposable {

    private class Entry(val stamp: Stamp, val pkg: SwiftPackage?)

    private data class Stamp(val modified: Long, val size: Long)

    private val cache = ConcurrentHashMap<Path, Entry>()
    private val loading = ConcurrentHashMap.newKeySet<Path>()

    @Volatile
    private var disposed = false

    init {
        // Packages trusted now can be described: look at the open files' targets again. Trust
        // changes are rare, so any of them refreshes this project's markers.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(TrustedProjectsListener.TOPIC, object : TrustedProjectsListener {
            override fun onProjectTrusted(locatedProject: TrustedProjectsLocator.LocatedProject) {
                if (!disposed && !project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Swift package trusted")
            }
        })
    }

    /**
     * The target containing [file], or null if it is not in a SwiftPM target directory. Sources
     * under a `.build` directory (dependency checkouts, generated files) belong to no target here:
     * describing a checkout would make SwiftPM resolve and build inside it.
     */
    fun targetOf(file: VirtualFile): SwiftTargetInfo? {
        if (generateSequence(file) { it.parent }.any { it.name == SwiftPackage.BUILD_DIR }) return null
        importedTargetOf(file)?.let { return it }
        val packageDir = packageRoot(file) ?: return null
        val relative = VfsUtilCore.getRelativePath(file, packageDir) ?: return null
        val pkg = localPath(packageDir)?.let(::cached)
        if (pkg != null) {
            val path = Path.of(file.path)
            val target = pkg.targets.filter { path.startsWith(it.path) }.maxByOrNull { it.path.nameCount } ?: return null
            val products = pkg.products.filter { it.kind == SwiftProductKind.EXECUTABLE && target.name in it.targets }.map { it.name }
            return SwiftTargetInfo(packageDir, target.name, target.moduleName, target.kind, products)
        }
        val segments = relative.split('/')
        if (segments.size < 3) return null
        val kind = when (segments[0]) {
            in TEST_DIRECTORIES -> SwiftTargetKind.TEST
            in SOURCE_DIRECTORIES -> null
            else -> return null
        }
        return SwiftTargetInfo(packageDir, segments[1], c99Name(segments[1]), kind, emptyList())
    }

    /**
     * The target from the SwiftPM project import, for packages that are linked and loaded. It is
     * exact (custom `path:` targets included) and needs no SwiftPM run, so it comes before the
     * description cache, which serves packages that are not linked.
     */
    private fun importedTargetOf(file: VirtualFile): SwiftTargetInfo? {
        if (project.isDisposed) return null
        val module = SwiftPmProjectModel.moduleFor(project, file) ?: return null
        val target = SwiftPmProjectModel.targetOf(module) ?: return null
        val packageDir = LocalFileSystem.getInstance().findFileByNioFile(Path.of(target.packagePath)) ?: return null
        val products = SwiftPmProjectModel.productsOf(module)
            .filter { it.kind == SwiftProductKind.EXECUTABLE && target.name in it.targets }
            .map { it.name }
        return SwiftTargetInfo(packageDir, target.name, target.moduleName, target.kind, products)
    }

    /** The description of the package in [packageDir] if it is cached and current; otherwise starts loading it. */
    fun cached(packageDir: Path): SwiftPackage? {
        val stamp = stamp(packageDir) ?: return null
        val entry = cache[packageDir]
        if (entry != null && entry.stamp == stamp) return entry.pkg
        scheduleLoad(packageDir)
        return null
    }

    /** Describes the package in [packageDir], from the cache if current. Runs SwiftPM, so call it off the UI thread. */
    fun load(packageDir: Path, cancelled: () -> Boolean = { false }): SwiftPackage {
        val stamp = stamp(packageDir) ?: throw IllegalArgumentException("No ${SwiftPackage.MANIFEST} in $packageDir")
        cache[packageDir]?.takeIf { it.stamp == stamp }?.pkg?.let { return it }
        if (!isTrusted(packageDir)) throw CannotDescribeException("The package in $packageDir is not trusted.")
        val toolchain = SwiftToolchainSettings.toolchain()?.takeIf { it.isValid }
            ?: throw CannotDescribeException("No usable Swift toolchain. Configure it in Settings | Languages & Frameworks | Swift.")
        val describe = SwiftCommandRunner.runChecked(SwiftPackageCommands(toolchain, packageDir).describe(), cancelled)
        // SwiftPM prints physical paths; the editor's files keep the symbolic links they were opened through.
        val pkg = SwiftPmPathMapping(packageDir).map(SwiftPackageJson.parsePackage(packageDir.fileName.toString().lowercase(), describe, null))
        cache[packageDir] = Entry(stamp, pkg)
        return pkg
    }

    /**
     * Whether SwiftPM may run for [packageDir]: describing a package compiles and runs its
     * manifest, so only for packages the user trusts, as for syncs and builds.
     */
    private fun isTrusted(packageDir: Path): Boolean = TrustedProjects.isProjectTrusted(packageDir, project)

    /** Why a package cannot be described now, though it may be later: not its manifest's fault, so not remembered. */
    class CannotDescribeException(message: String) : Exception(message)

    private fun scheduleLoad(packageDir: Path) {
        // Until a package is trusted, its targets come from the directory layout.
        if (disposed || !isTrusted(packageDir) || !loading.add(packageDir)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                load(packageDir) { disposed || project.isDisposed }
                if (!disposed && !project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("SwiftPM package described")
            } catch (e: CannotDescribeException) {
                LOG.info("Cannot describe the Swift package in $packageDir: ${e.message}")
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) return@execute
                LOG.info("Cannot describe the Swift package in $packageDir: ${e.message}")
                // Remember the failure for this manifest, so it is not retried until the manifest changes.
                stamp(packageDir)?.let { cache[packageDir] = Entry(it, null) }
            } finally {
                loading.remove(packageDir)
            }
        }
    }

    private fun stamp(packageDir: Path): Stamp? = try {
        val manifest = packageDir.resolve(SwiftPackage.MANIFEST)
        Stamp(Files.getLastModifiedTime(manifest).toMillis(), Files.size(manifest))
    } catch (_: java.io.IOException) {
        null
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private val LOG = logger<SwiftPackageDescriptions>()

        /** SwiftPM's predefined source and test directories. */
        private val SOURCE_DIRECTORIES = setOf("Sources", "Source", "src", "srcs")
        private val TEST_DIRECTORIES = setOf("Tests")

        fun getInstance(project: Project): SwiftPackageDescriptions = project.service()

        /** The nearest directory at or above [file] that contains a `Package.swift`. */
        fun packageRoot(file: VirtualFile): VirtualFile? {
            var dir: VirtualFile? = if (file.isDirectory) file else file.parent
            while (dir != null) {
                val manifest = dir.findChild(SwiftPackage.MANIFEST)
                if (manifest != null && !manifest.isDirectory) return dir
                dir = dir.parent
            }
            return null
        }

        /** The local path of [file], or null if it is not on the local file system. */
        fun localPath(file: VirtualFile): Path? = if (file.isInLocalFileSystem) Path.of(file.path) else null

        /** The module name SwiftPM derives from a target name (its `c99name`). */
        fun c99Name(name: String): String {
            val mapped = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
            return if (mapped.firstOrNull()?.isDigit() == true) "_$mapped" else mapped
        }
    }
}
