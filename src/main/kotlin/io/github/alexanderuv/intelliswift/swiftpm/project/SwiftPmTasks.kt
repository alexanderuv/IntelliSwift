package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands

/**
 * The tasks the SwiftPM tool window shows and the task manager runs. A task name is a single
 * word, `<command>` or `<command>:<target or product>`, so that it survives being written into the
 * space-separated *Tasks* field of an External System run configuration.
 */
sealed class SwiftPmTask(val name: String) {
    /** The `swift` command line for this task; [arguments] are the run configuration's arguments. */
    abstract fun commandLine(commands: SwiftPackageCommands, arguments: List<String>): GeneralCommandLine

    /** `swift build`. */
    data object Build : SwiftPmTask(BUILD) {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) = commands.build(extraArgs = arguments)
    }

    /** `swift build --target <target>`. */
    data class BuildTarget(val target: String) : SwiftPmTask("$BUILD:$target") {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) =
            commands.build(target = target, extraArgs = arguments)
    }

    /** `swift test`. */
    data object Test : SwiftPmTask(TEST) {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) = commands.test(extraArgs = arguments)
    }

    /** `swift package clean`. */
    data object Clean : SwiftPmTask(CLEAN) {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) = commands.clean().withArguments(arguments)
    }

    /** `swift package resolve`. */
    data object Resolve : SwiftPmTask(RESOLVE) {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) = commands.resolve().withArguments(arguments)
    }

    /** `swift package update`. */
    data object Update : SwiftPmTask(UPDATE) {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) = commands.update().withArguments(arguments)
    }

    /** `swift run <product>`; the arguments go to the program. */
    data class Run(val product: String) : SwiftPmTask("$RUN:$product") {
        override fun commandLine(commands: SwiftPackageCommands, arguments: List<String>) =
            commands.run(product, programArgs = arguments)
    }

    override fun toString(): String = name

    companion object {
        const val BUILD = "build"
        const val TEST = "test"
        const val CLEAN = "clean"
        const val RESOLVE = "resolve"
        const val UPDATE = "update"
        const val RUN = "run"

        /** Task groups, as the tool window shows them. */
        const val GROUP_BUILD = "build"
        const val GROUP_VERIFICATION = "verification"
        const val GROUP_DEPENDENCIES = "dependencies"
        const val GROUP_RUN = "run"

        /** Parses a task name; `null` if it is not a SwiftPM task. */
        fun parse(name: String): SwiftPmTask? {
            val command = name.substringBefore(':')
            val argument = name.substringAfter(':', missingDelimiterValue = "").takeIf { ':' in name }
            return when {
                argument == null -> when (command) {
                    BUILD -> Build
                    TEST -> Test
                    CLEAN -> Clean
                    RESOLVE -> Resolve
                    UPDATE -> Update
                    else -> null
                }
                argument.isEmpty() -> null
                command == BUILD -> BuildTarget(argument)
                command == RUN -> Run(argument)
                else -> null
            }
        }

        private fun GeneralCommandLine.withArguments(arguments: List<String>): GeneralCommandLine = apply { addParameters(arguments) }
    }
}
