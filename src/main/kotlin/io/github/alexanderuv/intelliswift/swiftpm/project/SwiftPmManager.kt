package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.ExternalSystemConfigurableAware
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.ExternalSystemUiAware
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.util.Function
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmConfigurable
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmExecutionSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmLocalSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmProjectSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettingsListener
import java.nio.file.Path
import javax.swing.Icon

/**
 * The SwiftPM External System. It runs in the IDE process (see the `SWIFTPM.system.in.process`
 * registry key in plugin.xml): resolving and running tasks just start `swift` processes.
 *
 * As [ExternalSystemAutoImportAware] it tells the platform's auto-reload which files a linked
 * package depends on: the manifests and `Package.resolved` of the root and of every editable
 * package of its graph. The platform registers an `ExternalSystemProjectAware` for each linked
 * package from this, marks the package as needing a reload when those files change, and reloads
 * it by itself when *Settings | Build Tools | Reload project after changes* allows.
 */
class SwiftPmManager :
    ExternalSystemManager<SwiftPmProjectSettings, SwiftPmSettingsListener, SwiftPmSettings, SwiftPmLocalSettings, SwiftPmExecutionSettings>,
    ExternalSystemAutoImportAware,
    ExternalSystemConfigurableAware,
    ExternalSystemUiAware {

    override fun getSystemId() = SwiftPm.SYSTEM_ID

    override fun getSettingsProvider(): Function<Project, SwiftPmSettings> = Function { SwiftPmSettings.getInstance(it) }

    override fun getLocalSettingsProvider(): Function<Project, SwiftPmLocalSettings> = Function { SwiftPmLocalSettings.getInstance(it) }

    override fun getExecutionSettingsProvider(): Function<Pair<Project, String>, SwiftPmExecutionSettings> =
        Function { projectAndPath -> SwiftPmExecutionSettings().apply { ideProjectPath = projectAndPath.first.basePath } }

    override fun getProjectResolverClass(): Class<SwiftPmProjectResolver> = SwiftPmProjectResolver::class.java

    override fun getTaskManagerClass(): Class<SwiftPmTaskManager> = SwiftPmTaskManager::class.java

    override fun getExternalProjectDescriptor(): FileChooserDescriptor = packageChooserDescriptor()

    /** Only used out of process, which SwiftPM never is. */
    override fun enhanceRemoteProcessing(parameters: SimpleJavaParameters) = Unit

    // ExternalSystemAutoImportAware

    override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
        val changed = Path.of(changedFileOrDirPath).toAbsolutePath().normalize()
        if (changed.fileName?.toString()?.let(SwiftPm::isSettingsFile) != true) return null
        val directory = changed.parent ?: return null
        return SwiftPmProjectModel.linkedPackagePaths(project).firstOrNull { linked ->
            directory in SwiftPmProjectModel.editablePackageDirectories(project, linked)
        }
    }

    override fun getAffectedExternalProjectFilePaths(externalProjectPath: String, project: Project): List<Path> =
        SwiftPmProjectModel.editablePackageDirectories(project, externalProjectPath).flatMap(SwiftPm::settingsFiles)

    // ExternalSystemConfigurableAware

    override fun getConfigurable(project: Project): Configurable = SwiftPmConfigurable(project)

    // ExternalSystemUiAware

    override fun getProjectRepresentationName(targetProjectPath: String, rootProjectPath: String?): String =
        Path.of(targetProjectPath).fileName?.toString() ?: targetProjectPath

    override fun getExternalProjectConfigDescriptor(): FileChooserDescriptor = packageChooserDescriptor()

    override fun getProjectIcon(): Icon = SwiftIcons.FILE

    override fun getTaskIcon(): Icon? = null

    companion object {
        /** Chooses a `Package.swift` or a directory; [SwiftPmPaths.packageDirectory] maps either to the package. */
        fun packageChooserDescriptor(): FileChooserDescriptor =
            FileChooserDescriptor(true, true, false, false, false, false)
                .withTitle("Select a SwiftPM Package")
                .withDescription("Select Package.swift or the directory that contains it")
                .withFileFilter { it.isDirectory || it.name == SwiftPackage.MANIFEST }
    }
}
