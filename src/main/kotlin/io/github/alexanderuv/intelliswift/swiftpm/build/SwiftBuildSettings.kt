package io.github.alexanderuv.intelliswift.swiftpm.build

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftBuildConfiguration

/** How *Build | Build Project* and the other IDE build actions run `swift build` in this project. */
@Service(Service.Level.PROJECT)
@State(name = "IntelliSwiftBuild", storages = [Storage("intelliswift.xml")])
class SwiftBuildSettings : SimplePersistentStateComponent<SwiftBuildSettings.BuildState>(BuildState()) {

    class BuildState : BaseState() {
        var configuration by enum(SwiftBuildConfiguration.DEBUG)
    }

    /** The `-c` configuration the IDE builds with. */
    var configuration: SwiftBuildConfiguration
        get() = state.configuration
        set(value) { state.configuration = value }

    companion object {
        fun getInstance(project: Project): SwiftBuildSettings = project.service()
    }
}
