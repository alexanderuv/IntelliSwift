package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.PackageDirectoryField
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

class SwiftTestSettingsEditor(private val project: Project) : SettingsEditor<SwiftTestConfiguration>() {
    private val packageDirectory = PackageDirectoryField(project, this)
    private val scope = ComboBox(SwiftTestScope.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.displayName }
    }
    private val testModule = ComboBox<String>().apply { isEditable = true }
    private val testSuite = JBTextField()
    private val testName = JBTextField()
    private val filterPreview = JBLabel()
    private val extraArguments = RawCommandLineEditor()
    private val environment = EnvironmentVariablesComponent(project)
    private val buildConfiguration = ComboBox(SwiftBuildConfiguration.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.displayName }
    }

    init {
        // The test targets of the package, from `swift package describe`, run off the UI thread.
        packageDirectory.onPackage { pkg ->
            val selected = moduleText()
            testModule.removeAllItems()
            pkg?.targets?.filter { it.kind.isTest }?.forEach { testModule.addItem(it.moduleName) }
            testModule.editor.item = selected
        }
        scope.addItemListener { if (it.stateChange == ItemEvent.SELECTED) updateFields() }
        testModule.addItemListener { updateFilterPreview() }
        (testModule.editor.editorComponent as? JTextField)?.document?.addDocumentListener(onChange { updateFilterPreview() })
        testSuite.document.addDocumentListener(onChange { updateFilterPreview() })
        testName.document.addDocumentListener(onChange { updateFilterPreview() })
    }

    override fun createEditor(): JComponent = panel {
        row("Package directory:") { cell(packageDirectory.component).align(AlignX.FILL) }
        row("Run:") { cell(scope) }
        row("Test target:") { cell(testModule).align(AlignX.FILL).comment("The module name of the test target.") }
        row("Class or suite:") {
            cell(testSuite).align(AlignX.FILL)
                .comment("An XCTest class, or a Swift Testing suite path such as Outer/Inner. Empty for a top-level @Test function.")
        }
        row("Test:") { cell(testName).align(AlignX.FILL).comment("An XCTest method, or a Swift Testing function such as names(name:).") }
        row("Filter:") { cell(filterPreview) }
        row("Extra arguments:") { cell(extraArguments).align(AlignX.FILL).comment("Passed to swift test.") }
        row { cell(environment).align(AlignX.FILL) }
        row("Build configuration:") { cell(buildConfiguration) }
    }

    override fun resetEditorFrom(configuration: SwiftTestConfiguration) {
        packageDirectory.text = configuration.packageDirectory.orEmpty()
        scope.selectedItem = configuration.scope
        testModule.editor.item = configuration.testModule.orEmpty()
        testSuite.text = configuration.testSuite.orEmpty()
        testName.text = configuration.testName.orEmpty()
        extraArguments.text = configuration.extraArguments.orEmpty()
        environment.envs = configuration.environment
        environment.isPassParentEnvs = configuration.passParentEnvironment
        buildConfiguration.selectedItem = configuration.buildConfiguration
        updateFields()
    }

    override fun applyEditorTo(configuration: SwiftTestConfiguration) {
        applyFields(configuration)
        configuration.extraArguments = extraArguments.text
        configuration.environment = environment.envs
        configuration.passParentEnvironment = environment.isPassParentEnvs
        configuration.buildConfiguration = buildConfiguration.selectedItem as? SwiftBuildConfiguration ?: SwiftBuildConfiguration.DEBUG
    }

    private fun applyFields(configuration: SwiftTestConfiguration) {
        val selectedScope = scope.selectedItem as? SwiftTestScope ?: SwiftTestScope.ALL
        configuration.packageDirectory = packageDirectory.text
        configuration.scope = selectedScope
        configuration.testModule = moduleText()
        configuration.testSuite = testSuite.text.takeIf { selectedScope == SwiftTestScope.SUITE || selectedScope == SwiftTestScope.TEST }
        configuration.testName = testName.text.takeIf { selectedScope == SwiftTestScope.TEST }
    }

    private fun moduleText(): String = (testModule.editor.item ?: "").toString().trim()

    private fun updateFields() {
        val selected = scope.selectedItem as? SwiftTestScope ?: SwiftTestScope.ALL
        testModule.isEnabled = selected != SwiftTestScope.ALL
        testSuite.isEnabled = selected == SwiftTestScope.SUITE || selected == SwiftTestScope.TEST
        testName.isEnabled = selected == SwiftTestScope.TEST
        updateFilterPreview()
    }

    /** Shows the `--filter` the fields give. */
    private fun updateFilterPreview() {
        val selected = scope.selectedItem as? SwiftTestScope ?: SwiftTestScope.ALL
        val filter = SwiftTestConfiguration.filterOf(selected, moduleText(), testSuite.text.trim(), testName.text.trim())
        filterPreview.text = filter?.let { "--filter $it" } ?: "(all tests)"
    }

    private fun onChange(action: () -> Unit) = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = action()
    }
}
