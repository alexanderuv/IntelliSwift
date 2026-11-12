package io.github.alexanderuv.intelliswift.swiftpm.project.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectSettingsControl
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalSystemConfigurable
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchainConfigurable
import java.awt.GridBagConstraints
import java.awt.Insets

/**
 * *Settings | Build, Execution, Deployment | Build Tools | SwiftPM*: the linked packages, with
 * the External System's per-package settings. The Swift toolchain is chosen on its own page
 * under *Languages & Frameworks*, which this page links to.
 */
class SwiftPmConfigurable(project: Project) :
    AbstractExternalSystemConfigurable<SwiftPmProjectSettings, SwiftPmSettingsListener, SwiftPmSettings>(project, SwiftPm.SYSTEM_ID) {

    override fun getId(): String = ID

    override fun getHelpTopic(): String? = null

    override fun newProjectSettings(): SwiftPmProjectSettings = SwiftPmProjectSettings()

    override fun createProjectSettingsControl(settings: SwiftPmProjectSettings): ExternalSystemSettingsControl<SwiftPmProjectSettings> =
        SwiftPmProjectSettingsControl(project, settings)

    override fun createSystemSettingsControl(settings: SwiftPmSettings): ExternalSystemSettingsControl<SwiftPmSettings> =
        ToolchainLinkControl(project)

    companion object {
        const val ID: String = "reference.settingsdialog.project.swiftpm"
    }
}

/** Per-package settings: only the External System's common ones so far. */
class SwiftPmProjectSettingsControl(project: Project, settings: SwiftPmProjectSettings) :
    AbstractExternalProjectSettingsControl<SwiftPmProjectSettings>(project, settings) {

    override fun fillExtraControls(content: PaintAwarePanel, indentLevel: Int) = Unit
    override fun isExtraSettingModified(): Boolean = false
    override fun resetExtraSettings(isDefaultModuleCreation: Boolean) = Unit
    override fun applyExtraSettings(settings: SwiftPmProjectSettings) = Unit

    @Throws(ConfigurationException::class)
    override fun validate(settings: SwiftPmProjectSettings): Boolean = true
}

/** Points to the Swift toolchain page, where the `swift` that runs SwiftPM is chosen. */
private class ToolchainLinkControl(private val project: Project) : ExternalSystemSettingsControl<SwiftPmSettings> {
    private val link: ActionLink = ActionLink("Swift toolchain: Languages & Frameworks | Swift") { openToolchainPage() }

    /** Switches the open Settings dialog to the toolchain page, or opens one on it. */
    private fun openToolchainPage() {
        val settings = DataManager.getInstance().getDataContext(link).getData(Settings.KEY)
        val page = settings?.find(TOOLCHAIN_CONFIGURABLE_ID)
        if (settings != null && page != null) {
            settings.select(page)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SwiftToolchainConfigurable::class.java)
        }
    }

    override fun fillUi(canvas: PaintAwarePanel, indentLevel: Int) {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            insets = Insets(0, JBUI.scale(indentLevel * INDENT), 0, 0)
        }
        canvas.add(link, constraints)
    }

    override fun reset() = Unit
    override fun isModified(): Boolean = false
    override fun apply(settings: SwiftPmSettings) = Unit
    override fun validate(settings: SwiftPmSettings): Boolean = true
    override fun disposeUIResources() = Unit
    override fun showUi(show: Boolean) {
        link.isVisible = show
    }

    private companion object {
        const val INDENT = 16

        /** The id `SwiftToolchainConfigurable` is registered with. */
        const val TOOLCHAIN_CONFIGURABLE_ID = "intelliswift.toolchain"
    }
}
