package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

/** *Settings | Languages & Frameworks | Swift*: the toolchain used for SwiftPM. */
class SwiftToolchainConfigurable : BoundSearchableConfigurable("Swift", "intelliswift.toolchain") {
    private val settings get() = SwiftToolchainSettings.getInstance()
    private val status = JBLabel()

    override fun createPanel(): DialogPanel {
        lateinit var field: TextFieldWithBrowseButton
        val panel = panel {
            group("Toolchain") {
                row("Swift executable:") {
                    field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withTitle("Select the swift Executable"),
                    ).align(AlignX.FILL)
                        .bindText({ settings.swiftPath.orEmpty() }, { settings.swiftPath = it })
                        .comment("Leave empty to detect: PATH, xcrun, swiftly and the standard install locations.")
                        .component
                }
                row { cell(status) }
            }
        }
        field.textField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = describe(field.text)
        })
        describe(settings.swiftPath.orEmpty())
        return panel
    }

    /** Shows which toolchain [path] (or detection, for an empty path) resolves to, computed off the UI thread. */
    private fun describe(path: String) {
        status.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val toolchain = if (path.isBlank()) settings.detectedToolchains(refresh = true).firstOrNull() else SwiftToolchain(Path.of(path.trim()))
            val text = when {
                toolchain == null -> "No Swift toolchain found."
                !toolchain.isValid -> "Not an executable: ${toolchain.swift}"
                else -> when (val version = toolchain.version) {
                    null -> "Could not run ${toolchain.swift} --version"
                    else -> buildString {
                        append("Swift ").append(version).append(" — ").append(toolchain.swift)
                        if (version < SwiftVersion.MINIMUM) append(" (Swift ${SwiftVersion.MINIMUM} or later is required)")
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater({ status.text = text }, ModalityState.any())
        }
    }
}
