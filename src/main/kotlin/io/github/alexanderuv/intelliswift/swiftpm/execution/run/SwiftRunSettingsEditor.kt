package io.github.alexanderuv.intelliswift.swiftpm.execution.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.PackageDirectoryField
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import javax.swing.JComponent

class SwiftRunSettingsEditor(private val project: Project) : SettingsEditor<SwiftRunConfiguration>() {
    private val packageDirectory = PackageDirectoryField(project, this)
    private val product = ComboBox<String>().apply { isEditable = true }
    private val programArguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
    }
    private val environment = EnvironmentVariablesComponent(project)
    private val buildConfiguration = ComboBox(SwiftBuildConfiguration.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.displayName }
    }

    init {
        // The executable products of the package, from `swift package describe`, run off the UI thread.
        packageDirectory.onPackage { pkg ->
            val selected = product.editor.item?.toString()
            product.removeAllItems()
            pkg?.products?.filter { it.kind == SwiftProductKind.EXECUTABLE }?.forEach { product.addItem(it.name) }
            product.selectedItem = selected?.ifBlank { null } ?: product.getItemAt(0)
        }
    }

    override fun createEditor(): JComponent = panel {
        row("Package directory:") { cell(packageDirectory.component).align(AlignX.FILL) }
        row("Executable product:") {
            cell(product).align(AlignX.FILL)
                .comment("Executable products of the package. Leave empty to run its only executable.")
        }
        row("Program arguments:") { cell(programArguments).align(AlignX.FILL) }
        row("Working directory:") {
            cell(workingDirectory).align(AlignX.FILL).comment("Leave empty to run in the package directory.")
        }
        row { cell(environment).align(AlignX.FILL) }
        row("Build configuration:") { cell(buildConfiguration) }
    }

    override fun resetEditorFrom(configuration: SwiftRunConfiguration) {
        packageDirectory.text = configuration.packageDirectory.orEmpty()
        product.selectedItem = configuration.product
        product.editor.item = configuration.product.orEmpty()
        programArguments.text = configuration.programArguments.orEmpty()
        workingDirectory.text = configuration.workingDirectory.orEmpty()
        environment.envs = configuration.environment
        environment.isPassParentEnvs = configuration.passParentEnvironment
        buildConfiguration.selectedItem = configuration.buildConfiguration
    }

    override fun applyEditorTo(configuration: SwiftRunConfiguration) {
        configuration.packageDirectory = packageDirectory.text
        configuration.product = product.editor.item?.toString()
        configuration.programArguments = programArguments.text
        configuration.workingDirectory = workingDirectory.text
        configuration.environment = environment.envs
        configuration.passParentEnvironment = environment.isPassParentEnvs
        configuration.buildConfiguration = buildConfiguration.selectedItem as? SwiftBuildConfiguration ?: SwiftBuildConfiguration.DEBUG
    }
}
