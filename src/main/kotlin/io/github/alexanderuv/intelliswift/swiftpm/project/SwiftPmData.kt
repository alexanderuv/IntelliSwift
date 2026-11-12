package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.serialization.PropertyMapping
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind
import java.io.Serializable

/*
 * SwiftPM facts the IDE project model has no place for, kept in the imported External System
 * data so that other features (build, run and test) can map modules and files back to SwiftPM.
 * The data nodes are cached by the platform between sessions, so these classes keep a
 * `@PropertyMapping` constructor for its serializer.
 *
 * Where they sit in the `DataNode<ProjectData>` tree:
 *
 * ```
 * ProjectData                       the linked root package
 * ├─ SwiftPackageData               one per checkout (read-only) package
 * ├─ LibraryData                    one per product of a checkout package
 * └─ ModuleData                     one per editable package (root and `path:` dependencies)
 *    ├─ SwiftPackageData
 *    ├─ SwiftProductData            one per product
 *    ├─ TaskData                    build, test, clean, resolve, update, run:<product>
 *    └─ ModuleData                  one per target
 *       ├─ SwiftTargetData
 *       └─ TaskData                 build:<target>
 * ```
 */

/** A package of the graph. Under its package module, or under the project node for a checkout. */
class SwiftPackageData @PropertyMapping("identity", "name", "packagePath", "editable", "version", "toolsVersion") constructor(
    /** SwiftPM's package identity, unique within the graph. */
    val identity: String,
    val name: String,
    /** The package directory, system-independent. */
    val packagePath: String,
    /** The root, a `path:` dependency or a package in edit mode; `false` for a checkout under `.build`. */
    val editable: Boolean,
    /** The resolved version, `unspecified` for local packages. */
    val version: String,
    val toolsVersion: String?,
) : Serializable {
    override fun toString(): String = "SwiftPackageData($identity at $packagePath)"

    companion object {
        @JvmField
        val KEY: Key<SwiftPackageData> = Key.create(SwiftPackageData::class.java, ProjectKeys.TASK.processingWeight + 10)
    }
}

/** A target, under the module it is mapped to. */
class SwiftTargetData @PropertyMapping("packageIdentity", "packagePath", "name", "moduleName", "kind", "targetPath", "sources", "productMemberships") constructor(
    val packageIdentity: String,
    /** The directory of the target's package, system-independent: what `--package-path` takes. */
    val packagePath: String,
    val name: String,
    /** The Swift module name (`c99name`), as used in `import` and in test identifiers. */
    val moduleName: String,
    val kind: SwiftTargetKind,
    /** The target directory, system-independent. */
    val targetPath: String,
    /** Source files, relative to [targetPath]. */
    val sources: List<String>,
    /** Products of the package that contain this target, directly or through its dependencies. */
    val productMemberships: List<String>,
) : Serializable {
    override fun toString(): String = "SwiftTargetData($packageIdentity/$name)"

    companion object {
        @JvmField
        val KEY: Key<SwiftTargetData> = Key.create(SwiftTargetData::class.java, ProjectKeys.TASK.processingWeight + 20)
    }
}

/** A product of an editable package, under the package module. */
class SwiftProductData @PropertyMapping("packageIdentity", "packagePath", "name", "kind", "targets") constructor(
    val packageIdentity: String,
    val packagePath: String,
    val name: String,
    val kind: SwiftProductKind,
    val targets: List<String>,
) : Serializable {
    override fun toString(): String = "SwiftProductData($packageIdentity/$name)"

    companion object {
        @JvmField
        val KEY: Key<SwiftProductData> = Key.create(SwiftProductData::class.java, ProjectKeys.TASK.processingWeight + 30)
    }
}
