package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration

/** *Settings | Build, Execution, Deployment | Build Tools | SwiftPM | Build*. */
class SwiftBuildConfigurable(private val project: Project) : BoundSearchableConfigurable("Build", ID) {
    private val settings get() = SwiftBuildSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        row("Build configuration:") {
            comboBox(SwiftBuildConfiguration.entries, textListCellRenderer { it?.displayName })
                .bindItem({ settings.configuration }, { settings.configuration = it ?: SwiftBuildConfiguration.DEBUG })
                .comment("Used by Build Project, Build Module and Rebuild (<code>swift build -c debug|release</code>).")
        }
    }

    companion object {
        const val ID: String = "intelliswift.swiftpm.build"
    }
}
