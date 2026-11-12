package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.util.Alarm
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.event.DocumentEvent

/**
 * The package directory field of the run configuration editors. When the directory changes, the
 * package is described with `swift package describe` off the UI thread, and the listeners
 * registered with [onPackage] get the result (null if it is not a package or cannot be described).
 */
class PackageDirectoryField(private val project: Project, parent: Disposable) {
    val component = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("SwiftPM Package Directory"))
    }

    private val listeners = ArrayList<(SwiftPackage?) -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    var text: String
        get() = component.text.trim()
        set(value) { component.text = value }

    init {
        component.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleDescribe()
        })
    }

    fun onPackage(listener: (SwiftPackage?) -> Unit) {
        listeners += listener
    }

    private fun scheduleDescribe() {
        val directory = text
        alarm.cancelAllRequests()
        alarm.addRequest({
            val pkg = describe(directory)
            ApplicationManager.getApplication().invokeLater({
                if (text == directory) listeners.forEach { it(pkg) }
            }, ModalityState.stateForComponent(component))
        }, DEBOUNCE_MS)
    }

    private fun describe(directory: String): SwiftPackage? {
        if (directory.isEmpty()) return null
        val path = runCatching { Path.of(directory) }.getOrNull() ?: return null
        if (!Files.isRegularFile(path.resolve(SwiftPackage.MANIFEST))) return null
        return try {
            SwiftPackageDescriptions.getInstance(project).load(path) { project.isDisposed }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            // Not describable (no toolchain, broken manifest): the fields stay free-form.
            null
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300
    }
}
