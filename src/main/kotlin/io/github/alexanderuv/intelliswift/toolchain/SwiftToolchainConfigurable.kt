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
import java.util.concurrent.atomic.AtomicInteger

/** *Settings | Languages & Frameworks | Swift*: the toolchain used for SwiftPM. */
class SwiftToolchainConfigurable : BoundSearchableConfigurable("Swift", "intelliswift.toolchain") {
    private val settings get() = SwiftToolchainSettings.getInstance()
    private val status = JBLabel()
    private val requests = AtomicInteger()

    override fun createPanel(): DialogPanel {
        lateinit var field: TextFieldWithBrowseButton
        val panel = panel {
            group("Toolchain") {
                row("Swift executable:") {
                    field = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withTitle("Select the swift Executable"),
                    ).align(AlignX.FILL)
                        .bindText({ settings.swiftPath.orEmpty() }, { settings.swiftPath = it })
                        .validationOnApply {
                            val path = SwiftToolchainSettings.normalizePath(it.text)
                            if (path != null && SwiftToolchainSettings.toolchainAt(path) == null) error("Not a valid path: $path") else null
                        }
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

    /**
     * Shows the path as stored, normalized ([SwiftToolchainSettings.normalizePath]), so the page
     * is not left modified by surrounding spaces or quotes it dropped.
     */
    override fun apply() {
        super.apply()
        reset()
    }

    /**
     * Shows which toolchain [text] (or detection, for an empty path) resolves to, computed off the
     * UI thread. Each edit supersedes the checks still running, which may finish in any order.
     */
    private fun describe(text: String) {
        val request = requests.incrementAndGet()
        status.text = "Checking…"
        val path = SwiftToolchainSettings.normalizePath(text)
        ApplicationManager.getApplication().executeOnPooledThread {
            val toolchain = if (path == null) settings.defaultToolchain(refresh = true) else SwiftToolchainSettings.toolchainAt(path)
            val message = when {
                toolchain == null && path == null -> "No Swift toolchain found."
                toolchain == null -> "Not a valid path: $path"
                !toolchain.isValid -> "Not an executable: ${toolchain.swift}"
                else -> when (val version = toolchain.version) {
                    null -> "Could not run ${toolchain.swift} --version"
                    else -> buildString {
                        append("Swift ").append(version).append(" — ").append(toolchain.swift)
                        if (version < SwiftVersion.MINIMUM) append(" (Swift ${SwiftVersion.MINIMUM} or later is required)")
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater({ if (request == requests.get()) status.text = message }, ModalityState.any())
        }
    }
}
