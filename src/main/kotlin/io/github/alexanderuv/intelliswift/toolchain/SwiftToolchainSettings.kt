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

    /** The configured toolchain, or the [defaultToolchain]. Call off the UI thread. */
    fun toolchain(): SwiftToolchain? =
        swiftPath?.let { SwiftToolchain(Path.of(it)) } ?: defaultToolchain()

    /**
     * The toolchain used when none is configured: the first detected one that is Swift
     * [SwiftVersion.MINIMUM] or later. Runs `swift --version`, so call it off the UI thread.
     */
    fun defaultToolchain(refresh: Boolean = false): SwiftToolchain? =
        preferredToolchain(detectedToolchains(refresh)) { it.version }

    companion object {
        fun getInstance(): SwiftToolchainSettings = service()

        /** The toolchain to run SwiftPM with, or `null` if none is configured or installed. */
        fun toolchain(): SwiftToolchain? = getInstance().toolchain()

        /**
         * The first of [toolchains], in detection order, that is a supported version. When none
         * is, the first one, so that callers report its version as too old rather than finding
         * no toolchain at all.
         */
        internal fun preferredToolchain(toolchains: List<SwiftToolchain>, versionOf: (SwiftToolchain) -> SwiftVersion?): SwiftToolchain? =
            toolchains.firstOrNull { versionOf(it)?.let { version -> version >= SwiftVersion.MINIMUM } == true } ?: toolchains.firstOrNull()
    }
}
