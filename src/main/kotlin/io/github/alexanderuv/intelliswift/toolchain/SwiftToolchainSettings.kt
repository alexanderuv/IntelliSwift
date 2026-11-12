package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Path

/** The Swift toolchain the IDE uses, chosen in *Settings | Languages & Frameworks | Swift*. */
@Service(Service.Level.APP)
@State(name = "IntelliSwiftToolchain", storages = [Storage("intelliswift.xml")])
class SwiftToolchainSettings : SimplePersistentStateComponent<SwiftToolchainSettings.ToolchainState>(ToolchainState()) {

    class ToolchainState : BaseState() {
        /** Path of the `swift` executable; empty means "detect automatically". */
        var swiftPath by string()
    }

    var swiftPath: String?
        get() = state.swiftPath?.takeIf { it.isNotBlank() }
        set(value) { state.swiftPath = value?.trim()?.takeIf { it.isNotEmpty() } }

    @Volatile
    private var detected: List<SwiftToolchain>? = null

    /**
     * Installed toolchains, detected once per session (see [SwiftToolchainDetector]). Detection
     * starts processes, so call this off the UI thread.
     */
    fun detectedToolchains(refresh: Boolean = false): List<SwiftToolchain> {
        if (!refresh) detected?.let { return it }
        return SwiftToolchainDetector.detect().also { detected = it }
    }

    /** The configured toolchain, or the first detected one. Call off the UI thread. */
    fun toolchain(): SwiftToolchain? =
        swiftPath?.let { SwiftToolchain(Path.of(it)) } ?: detectedToolchains().firstOrNull()

    companion object {
        fun getInstance(): SwiftToolchainSettings = service()

        /** The toolchain to run SwiftPM with, or `null` if none is configured or installed. */
        fun toolchain(): SwiftToolchain? = getInstance().toolchain()
    }
}
