package io.github.alexanderuv.intelliswift.swiftpm.model

import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftCommandRunner
import io.github.alexanderuv.intelliswift.swiftpm.cli.SwiftPackageCommands
import io.github.alexanderuv.intelliswift.toolchain.SwiftToolchain
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Loads a [SwiftPackageGraph] by running SwiftPM: `show-dependencies` for the root (which
 * resolves dependencies first if needed), then `describe` and `dump-package` for every package,
 * in parallel.
 */
class SwiftPackageGraphLoader(private val toolchain: SwiftToolchain) {

    fun load(
        root: Path,
        cancelled: () -> Boolean = { false },
        onOutput: ((text: String, outputType: Key<*>) -> Unit)? = null,
    ): SwiftPackageGraph {
        val dependencies = SwiftCommandRunner.runChecked(SwiftPackageCommands(toolchain, root).showDependencies(), cancelled, onOutput)
        val resolved = SwiftPackageJson.parseDependencies(dependencies)
        val executor = AppExecutorUtil.getAppExecutorService()
        val futures: Map<String, Future<Pair<String, String?>>> = resolved.flatten().associate { node ->
            node.identity to executor.submit<Pair<String, String?>> {
                val commands = SwiftPackageCommands(toolchain, node.path)
                SwiftCommandRunner.runChecked(commands.describe(), cancelled) to
                    SwiftCommandRunner.runChecked(commands.dumpPackage(), cancelled)
            }
        }
        try {
            return SwiftPackageGraph.build(dependencies) { node -> unwrap(futures.getValue(node.identity)) }
        } finally {
            futures.values.forEach { it.cancel(true) }
        }
    }

    private fun <T> unwrap(future: Future<T>): T = try {
        future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
