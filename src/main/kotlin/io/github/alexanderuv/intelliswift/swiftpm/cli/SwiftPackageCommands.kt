package io.github.alexanderuv.intelliswift.swiftpm.cli

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import java.nio.file.Path

/** SwiftPM's build configurations (`-c`). */
enum class SwiftBuildConfiguration(val cli: String, val displayName: String) {
    DEBUG("debug", "Debug"),
    RELEASE("release", "Release"),
}

/**
 * The SwiftPM command lines the plugin runs. Every command names its package with
 * `--package-path`, so the working directory stays free for the program being run.
 */
class SwiftPackageCommands(val toolchain: SwiftToolchain, val packagePath: Path) {

    private fun swift(subcommand: String, vararg args: String): GeneralCommandLine =
        toolchain.command(subcommand, "--package-path", packagePath.toString(), *args)
            .withWorkingDirectory(packagePath)

    /** `swift package <subcommand>`; `--package-path` is an option of `swift package` itself. */
    private fun pkg(subcommand: String, vararg args: String): GeneralCommandLine =
        toolchain.command("package", "--package-path", packagePath.toString(), subcommand, *args)
            .withWorkingDirectory(packagePath)

    fun describe(): GeneralCommandLine = pkg("describe", "--type", "json")

    fun dumpPackage(): GeneralCommandLine = pkg("dump-package")

    /** Resolves dependencies if needed and prints the dependency tree. */
    fun showDependencies(): GeneralCommandLine = pkg("show-dependencies", "--format", "json")

    fun resolve(): GeneralCommandLine = pkg("resolve")

    fun update(): GeneralCommandLine = pkg("update")

    fun clean(): GeneralCommandLine = pkg("clean")

    /**
     * `swift build`. With [target] or [product] only that is built; [buildTests] also builds the
     * test targets.
     */
    fun build(
        configuration: SwiftBuildConfiguration = SwiftBuildConfiguration.DEBUG,
        target: String? = null,
        product: String? = null,
        buildTests: Boolean = false,
        extraArgs: List<String> = emptyList(),
    ): GeneralCommandLine = swift("build", "-c", configuration.cli).apply {
        target?.let { addParameters("--target", it) }
        product?.let { addParameters("--product", it) }
        if (buildTests) addParameter("--build-tests")
        addParameters(extraArgs)
    }

    /**
     * `swift run [product] [args]`. SwiftPM passes everything after the product to the program
     * unchanged, including options and a `--` (which therefore must not be added). Without a
     * product the first argument would be taken for one, so [programArgs] need a [product].
     */
    fun run(
        product: String?,
        configuration: SwiftBuildConfiguration = SwiftBuildConfiguration.DEBUG,
        programArgs: List<String> = emptyList(),
        extraArgs: List<String> = emptyList(),
    ): GeneralCommandLine {
        require(product != null || programArgs.isEmpty()) { "Program arguments need an executable product" }
        return swift("run", "-c", configuration.cli).apply {
            addParameters(extraArgs)
            product?.let { addParameter(it) }
            addParameters(programArgs)
        }
    }

    /** `swift test`, optionally filtered; [extraArgs] come before the filters. */
    fun test(
        filters: List<String> = emptyList(),
        configuration: SwiftBuildConfiguration = SwiftBuildConfiguration.DEBUG,
        extraArgs: List<String> = emptyList(),
    ): GeneralCommandLine = swift("test", "-c", configuration.cli).apply {
        addParameters(extraArgs)
        for (filter in filters) addParameters("--filter", filter)
    }

    /** `swift build --show-bin-path`: the directory products are built into. */
    fun showBinPath(configuration: SwiftBuildConfiguration = SwiftBuildConfiguration.DEBUG): GeneralCommandLine =
        swift("build", "-c", configuration.cli, "--show-bin-path")
}
