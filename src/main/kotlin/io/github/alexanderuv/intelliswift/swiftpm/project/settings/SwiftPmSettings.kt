package io.github.alexanderuv.intelliswift.swiftpm.project.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import io.github.alexanderuv.intelliswift.swiftpm.project.SwiftPm
import java.util.TreeSet

/** Settings of one linked SwiftPM package; its external project path is the package directory. */
class SwiftPmProjectSettings() : ExternalProjectSettings() {
    constructor(packagePath: String) : this() {
        externalProjectPath = packagePath
    }

    override fun clone(): SwiftPmProjectSettings = SwiftPmProjectSettings().also { copyTo(it) }
}

/** Notified when SwiftPM packages are linked, unlinked or their settings change. */
interface SwiftPmSettingsListener : ExternalSystemSettingsListener<SwiftPmProjectSettings> {
    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<SwiftPmSettingsListener> = Topic(SwiftPmSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** The SwiftPM packages linked to an IDE project. */
@Service(Service.Level.PROJECT)
@State(name = "SwiftPmSettings", storages = [Storage("swiftpm.xml")])
class SwiftPmSettings(project: Project) :
    AbstractExternalSystemSettings<SwiftPmSettings, SwiftPmProjectSettings, SwiftPmSettingsListener>(SwiftPmSettingsListener.TOPIC, project),
    PersistentStateComponent<SwiftPmSettings.SettingsState> {

    override fun subscribe(listener: ExternalSystemSettingsListener<SwiftPmProjectSettings>, parentDisposable: Disposable) {
        doSubscribe(Forwarding(listener), parentDisposable)
    }

    override fun copyExtraSettingsFrom(settings: SwiftPmSettings) = Unit

    override fun checkSettings(old: SwiftPmProjectSettings, current: SwiftPmProjectSettings) = Unit

    override fun getState(): SettingsState = SettingsState().also { fillState(it) }

    override fun loadState(state: SettingsState) = super.loadState(state)

    class SettingsState : State<SwiftPmProjectSettings> {
        private var projects: MutableSet<SwiftPmProjectSettings> = TreeSet()

        @XCollection(elementTypes = [SwiftPmProjectSettings::class])
        override fun getLinkedExternalProjectsSettings(): MutableSet<SwiftPmProjectSettings> = projects

        override fun setLinkedExternalProjectsSettings(settings: MutableSet<SwiftPmProjectSettings>?) {
            projects = settings ?: TreeSet()
        }
    }

    /**
     * Adapts a generic listener to this system's topic. The overrides are explicit because the
     * listener methods are Java default methods, which Kotlin's `by` delegation does not forward.
     */
    private class Forwarding(private val delegate: ExternalSystemSettingsListener<SwiftPmProjectSettings>) : SwiftPmSettingsListener {
        override fun onProjectRenamed(oldName: String, newName: String) = delegate.onProjectRenamed(oldName, newName)
        override fun onProjectsLoaded(settings: Collection<SwiftPmProjectSettings>) = delegate.onProjectsLoaded(settings)
        override fun onProjectsLinked(settings: Collection<SwiftPmProjectSettings>) = delegate.onProjectsLinked(settings)
        override fun onProjectsUnlinked(linkedProjectPaths: Set<String>) = delegate.onProjectsUnlinked(linkedProjectPaths)
        override fun onBulkChangeStart() = delegate.onBulkChangeStart()
        override fun onBulkChangeEnd() = delegate.onBulkChangeEnd()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SwiftPmSettings = project.service()
    }
}

/** Per-workspace state the External System keeps for SwiftPM (recent tasks, sync state). */
@Service(Service.Level.PROJECT)
@State(name = "SwiftPmLocalSettings", storages = [Storage(StoragePathMacros.CACHE_FILE)])
class SwiftPmLocalSettings(project: Project) :
    AbstractExternalSystemLocalSettings<SwiftPmLocalSettings.LocalState>(SwiftPm.SYSTEM_ID, project, LocalState()),
    PersistentStateComponent<SwiftPmLocalSettings.LocalState> {

    override fun loadState(state: LocalState) = super.loadState(state)

    class LocalState : AbstractExternalSystemLocalSettings.State()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SwiftPmLocalSettings = project.service()
    }
}

/**
 * What a SwiftPM sync or task runs with. The toolchain comes from the application-wide Swift
 * settings; beyond the inherited environment and arguments, a sync needs to know where the IDE
 * project keeps its files.
 */
class SwiftPmExecutionSettings : ExternalSystemExecutionSettings() {
    /** The IDE project's directory, where the imported modules' `.iml` files go; `null` if it has none. */
    var ideProjectPath: String? = null

    companion object {
        private const val serialVersionUID = 1L
    }
}
