package io.github.alexanderuv.intelliswift.swiftpm.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/** Thrown when SwiftPM's JSON output does not have the expected shape. */
class SwiftPackageJsonException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the JSON of `swift package describe --type json`, `swift package dump-package` and
 * `swift package show-dependencies --format json`.
 *
 * `describe` has the resolved view of a package: target kinds, directories, sources, and the
 * names of the targets and products each target depends on. It does not say which package a
 * product comes from; `dump-package` does, for products declared as `.product(name:package:)`.
 */
object SwiftPackageJson {

    /** Parses one package from its `describe` and `dump-package` output. */
    fun parsePackage(identity: String, describe: String, dump: String?): SwiftPackage {
        val d = parseObject(describe, "swift package describe")
        val packagePath = Path.of(d.string("path") ?: throw SwiftPackageJsonException("describe output has no 'path'"))
        val productPackages = dump?.let { productPackages(parseObject(it, "swift package dump-package")) }.orEmpty()

        val targets = d.array("targets").mapObjects { t ->
            val name = t.string("name") ?: throw SwiftPackageJsonException("target without a name")
            val path = t.string("path")?.let { packagePath.resolve(it).normalize() } ?: packagePath
            val packageOf = productPackages[name].orEmpty()
            SwiftTarget(
                name = name,
                moduleName = t.string("c99name") ?: name,
                kind = SwiftTargetKind.of(t.string("type")),
                moduleType = SwiftModuleType.of(t.string("module_type")),
                path = path,
                sources = t.array("sources").strings(),
                targetDependencies = t.array("target_dependencies").strings(),
                productDependencies = t.array("product_dependencies").strings().map { SwiftProductDependency(it, packageOf[it]) },
                productMemberships = t.array("product_memberships").strings(),
            )
        }
        val products = d.array("products").mapObjects { p ->
            val kind = p.get("type")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()?.firstOrNull()
            SwiftProduct(p.string("name") ?: "", SwiftProductKind.of(kind), p.array("targets").strings())
        }
        return SwiftPackage(
            identity = identity,
            name = d.string("name") ?: packagePath.fileName.toString(),
            path = packagePath,
            toolsVersion = d.string("tools_version"),
            products = products,
            targets = targets,
        )
    }

    /** Parses the dependency tree; the root node is the package itself. */
    fun parseDependencies(json: String): SwiftResolvedPackage =
        resolvedNode(parseObject(json, "swift package show-dependencies"))

    private fun resolvedNode(o: JsonObject): SwiftResolvedPackage = SwiftResolvedPackage(
        identity = o.string("identity") ?: throw SwiftPackageJsonException("dependency without an identity"),
        name = o.string("name") ?: "",
        url = o.string("url") ?: "",
        version = o.string("version") ?: "unspecified",
        path = Path.of(o.string("path") ?: throw SwiftPackageJsonException("dependency without a path")),
        dependencies = o.array("dependencies").mapObjects(::resolvedNode),
    )

    /**
     * Per target, the package each `.product(name:package:)` dependency names. In `dump-package`
     * such a dependency is `{"product": [name, package, moduleAliases, condition]}`.
     */
    private fun productPackages(dump: JsonObject): Map<String, Map<String, String>> =
        dump.array("targets").mapObjects { t ->
            val products = t.array("dependencies").mapObjects { dep ->
                val product = dep.get("product")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapObjects null
                val name = product.getOrNull(0)?.stringOrNull() ?: return@mapObjects null
                val pkg = product.getOrNull(1)?.stringOrNull() ?: return@mapObjects null
                name to pkg
            }.filterNotNull().toMap()
            (t.string("name") ?: "") to products
        }.toMap()

    private fun parseObject(text: String, command: String): JsonObject {
        // SwiftPM may print warnings before the JSON document.
        val start = text.indexOf('{')
        if (start < 0) throw SwiftPackageJsonException("$command printed no JSON")
        val element = try {
            JsonParser.parseString(text.substring(start))
        } catch (e: Exception) {
            throw SwiftPackageJsonException("$command printed malformed JSON: ${e.message}", e)
        }
        if (!element.isJsonObject) throw SwiftPackageJsonException("$command did not print a JSON object")
        return element.asJsonObject
    }

    private fun JsonObject.string(key: String): String? = get(key)?.stringOrNull()

    private fun JsonElement.stringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonArray.getOrNull(i: Int): JsonElement? = if (i < size()) get(i) else null

    private fun JsonArray.strings(): List<String> = mapNotNull { it.stringOrNull() }

    private fun <R> JsonArray.mapObjects(transform: (JsonObject) -> R): List<R> =
        filter { it.isJsonObject }.map { transform(it.asJsonObject) }
}
