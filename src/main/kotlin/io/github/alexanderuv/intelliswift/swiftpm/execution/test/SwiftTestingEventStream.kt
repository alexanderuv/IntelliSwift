package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import io.github.alexanderuv.intelliswift.toolchain.SwiftVersion
import java.nio.file.Path

/** The `swift test` arguments that make Swift Testing write its JSON event stream to a file. */
object SwiftTestingEventStream {
    /** The event stream schema version the parser reads. */
    const val VERSION = "0"

    /**
     * The arguments for [version] of the toolchain, or none if it cannot write an event stream.
     *
     * - Swift 6.0 and later: `--event-stream-output-path <file> --event-stream-version 0`.
     *   SwiftPM 6.0 introduced these names, keeping `--experimental-event-stream-output` /
     *   `--experimental-event-stream-version` as legacy aliases; later releases removed those.
     * - Swift 6.0 development snapshots (`6.0-dev`) may predate the rename, so they get the
     *   experimental names, which every 6.0 snapshot with Swift Testing support accepts.
     * - Before 6.0, `swift test` does not run Swift Testing.
     *
     * An unknown version is assumed to be a current toolchain.
     */
    fun arguments(version: SwiftVersion?, file: Path): List<String> = when {
        version == null -> modern(file)
        version < SwiftVersion(6, 0) -> emptyList()
        version.major == 6 && version.minor == 0 && version.text.contains("-dev") -> listOf(
            "--experimental-event-stream-output", file.toString(),
            "--experimental-event-stream-version", VERSION,
        )
        else -> modern(file)
    }

    private fun modern(file: Path) = listOf("--event-stream-output-path", file.toString(), "--event-stream-version", VERSION)
}
