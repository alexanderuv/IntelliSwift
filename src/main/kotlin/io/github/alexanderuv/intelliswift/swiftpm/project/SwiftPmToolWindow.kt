package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.service.execution.AbstractExternalSystemTaskConfigurationType
import com.intellij.openapi.externalSystem.service.task.ui.AbstractExternalSystemToolWindowFactory
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.project.Project
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.swiftpm.project.settings.SwiftPmSettings
import javax.swing.Icon

/**
 * The SwiftPM tool window: the platform's External System view, listing each linked package
 * with its packages, targets, dependencies and tasks, with reload and link actions.
 */
class SwiftPmToolWindowFactory : AbstractExternalSystemToolWindowFactory(SwiftPm.SYSTEM_ID) {
    override fun getSettings(project: Project): AbstractExternalSystemSettings<*, *, *> = SwiftPmSettings.getInstance(project)
}

/**
 * The run configuration type of SwiftPM tasks. The External System runs a task from the tool
 * window, a shortcut or a before-run step through a configuration of this type.
 */
class SwiftPmTaskConfigurationType : AbstractExternalSystemTaskConfigurationType(SwiftPm.SYSTEM_ID) {
    override fun getIcon(): Icon = SwiftIcons.FILE
    override fun getConfigurationFactoryId(): String = "SwiftPM"
}
