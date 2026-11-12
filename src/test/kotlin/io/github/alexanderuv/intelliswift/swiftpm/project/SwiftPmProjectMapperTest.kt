package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.LibraryLevel
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftModuleType
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackage
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftPackageGraph
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProduct
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductDependency
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftProductKind
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftResolvedPackage
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTarget
import io.github.alexanderuv.intelliswift.swiftpm.model.SwiftTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** Maps package graphs built from the fixture JSON and checks the External System model. */
class SwiftPmProjectMapperTest {

    private val G = "/FIXTURE/Greeter"

    @Test fun `every editable package and every target is a module`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        assertEquals("Greeter", project.data.externalName)
        assertEquals(G, project.data.linkedExternalProjectPath)

        assertEquals(listOf("Greeter", "Utils"), packageModules(project).map { it.data.externalName })
        assertEquals(
            setOf("Greeter.greet", "Greeter.Lint", "Greeter.GreeterTests", "Greeter.Greeter", "Greeter.CHelpers"),
            targetModules(module(project, "Greeter")).map { it.data.externalName }.toSet(),
        )
        assertEquals(listOf("Utils.Utils"), targetModules(module(project, "Utils")).map { it.data.externalName })

        val all = ExternalSystemApiUtil.findAllRecursively(project, ProjectKeys.MODULE).map { it.data }
        assertTrue(all.all { it.moduleTypeId == SwiftPackageModuleType.ID })
        assertTrue(all.all { it.owner == SwiftPm.SYSTEM_ID })
        assertEquals("ids are unique", all.size, all.map { it.id }.toSet().size)
        assertEquals("greeter:Greeter", module(project, "Greeter.Greeter").data.id)
        assertTrue(ExternalSystemApiUtil.findAll(project, ProjectKeys.LIBRARY).isEmpty())
    }

    @Test fun `package modules own the package directory without build output`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        val root = contentRoots(module(project, "Greeter")).single()
        assertEquals(G, root.rootPath)
        assertEquals(setOf("$G/.build", "$G/.swiftpm"), root.getPaths(ExternalSystemSourceType.EXCLUDED).map { it.path }.toSet())
        assertTrue(root.getPaths(ExternalSystemSourceType.SOURCE).isEmpty())

        val utils = contentRoots(module(project, "Utils")).single()
        assertEquals("/FIXTURE/Utils", utils.rootPath)
        assertEquals(setOf("/FIXTURE/Utils/.build", "/FIXTURE/Utils/.swiftpm"), utils.getPaths(ExternalSystemSourceType.EXCLUDED).map { it.path }.toSet())
    }

    @Test fun `target modules have their directory as source or test root`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        fun roots(name: String, type: ExternalSystemSourceType) =
            contentRoots(module(project, name)).flatMap { it.getPaths(type) }.map { it.path }

        assertEquals(listOf("$G/Sources/Greeter"), contentRoots(module(project, "Greeter.Greeter")).map { it.rootPath })
        assertEquals(listOf("$G/Sources/Greeter"), roots("Greeter.Greeter", ExternalSystemSourceType.SOURCE))
        assertEquals(listOf("$G/Sources/CHelpers"), roots("Greeter.CHelpers", ExternalSystemSourceType.SOURCE))
        assertEquals(listOf("$G/Sources/greet"), roots("Greeter.greet", ExternalSystemSourceType.SOURCE))
        assertEquals(listOf("$G/Plugins/Lint"), roots("Greeter.Lint", ExternalSystemSourceType.SOURCE))
        assertEquals(listOf("$G/Tests/GreeterTests"), roots("Greeter.GreeterTests", ExternalSystemSourceType.TEST))
        assertTrue(roots("Greeter.GreeterTests", ExternalSystemSourceType.SOURCE).isEmpty())
        assertEquals(listOf("/FIXTURE/Utils/Sources/Utils"), roots("Utils.Utils", ExternalSystemSourceType.SOURCE))
    }

    @Test fun `target and editable product dependencies are module dependencies`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        assertEquals(setOf("Greeter.CHelpers", "Utils.Utils"), moduleDependencies(project, "Greeter.Greeter"))
        assertEquals(setOf("Greeter.Greeter"), moduleDependencies(project, "Greeter.greet"))
        assertEquals(setOf("Greeter.Greeter"), moduleDependencies(project, "Greeter.GreeterTests"))
        assertEquals(emptySet<String>(), moduleDependencies(project, "Greeter.CHelpers"))
        assertEquals(emptySet<String>(), moduleDependencies(project, "Greeter.Lint"))

        val dependencies = ExternalSystemApiUtil.findAll(module(project, "Greeter.Greeter"), ProjectKeys.MODULE_DEPENDENCY).map { it.data }
        assertTrue("transitive modules stay importable", dependencies.all { it.isExported })
        assertTrue(ExternalSystemApiUtil.findAllRecursively(project, ProjectKeys.LIBRARY_DEPENDENCY).isEmpty())
    }

    @Test fun `packages, targets and products carry their SwiftPM data`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        val greeter = ExternalSystemApiUtil.find(module(project, "Greeter"), SwiftPackageData.KEY)!!.data
        assertEquals("greeter", greeter.identity)
        assertEquals(G, greeter.packagePath)
        assertTrue(greeter.editable)
        assertTrue(ExternalSystemApiUtil.find(module(project, "Utils"), SwiftPackageData.KEY)!!.data.editable)

        val target = ExternalSystemApiUtil.find(module(project, "Greeter.GreeterTests"), SwiftTargetData.KEY)!!.data
        assertEquals("GreeterTests", target.name)
        assertEquals("GreeterTests", target.moduleName)
        assertEquals(SwiftTargetKind.TEST, target.kind)
        assertEquals(G, target.packagePath)
        assertEquals(listOf("GreeterTests.swift", "GreeterXCTests.swift"), target.sources)
        assertNull("package modules have no target", ExternalSystemApiUtil.find(module(project, "Greeter"), SwiftTargetData.KEY))

        val products = ExternalSystemApiUtil.findAll(module(project, "Greeter"), SwiftProductData.KEY).associate { it.data.name to it.data.kind }
        assertEquals(mapOf("Greeter" to SwiftProductKind.LIBRARY, "greet" to SwiftProductKind.EXECUTABLE, "Lint" to SwiftProductKind.PLUGIN), products)
    }

    @Test fun `packages have build, test, clean, resolve, update and run tasks, targets have build tasks`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph())
        assertEquals(listOf("build", "clean", "test", "resolve", "update", "run:greet"), tasks(module(project, "Greeter")))
        assertEquals(listOf("build", "clean", "resolve", "update"), tasks(module(project, "Utils")))
        assertEquals(listOf("build:Greeter"), tasks(module(project, "Greeter.Greeter")))
        assertEquals(listOf("build:GreeterTests"), tasks(module(project, "Greeter.GreeterTests")))
        assertEquals(listOf("build:Utils"), tasks(module(project, "Utils.Utils")))

        val greeterTasks = ExternalSystemApiUtil.findAll(module(project, "Greeter"), ProjectKeys.TASK).map { it.data }
        assertTrue(greeterTasks.all { it.linkedExternalProjectPath == G && it.owner == SwiftPm.SYSTEM_ID })
        assertTrue(greeterTasks.single { it.name == "test" }.isTest)
        val utilsBuild = ExternalSystemApiUtil.findAll(module(project, "Utils.Utils"), ProjectKeys.TASK).single().data
        assertEquals("/FIXTURE/Utils", utilsBuild.linkedExternalProjectPath)
    }

    @Test fun `checkout packages are libraries per product, with library dependencies`() {
        val checkout = "$G/.build/checkouts/Utils"
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph(utilsCheckout = true))

        assertEquals("checkouts are not modules", listOf("Greeter"), packageModules(project).map { it.data.externalName })
        val library = ExternalSystemApiUtil.findAll(project, ProjectKeys.LIBRARY).single().data
        assertEquals("utils:Utils", library.externalName)
        assertEquals("1.2.0", library.version)
        assertEquals(setOf("$checkout/Sources/Utils"), library.getPaths(LibraryPathType.SOURCE))
        assertTrue(library.getPaths(LibraryPathType.BINARY).isEmpty())

        val greeter = module(project, "Greeter.Greeter")
        assertEquals(setOf("Greeter.CHelpers"), moduleDependencies(project, "Greeter.Greeter"))
        val libraryDependency = ExternalSystemApiUtil.findAll(greeter, ProjectKeys.LIBRARY_DEPENDENCY).single().data
        assertEquals("utils:Utils", libraryDependency.target.externalName)
        assertEquals(LibraryLevel.PROJECT, libraryDependency.level)
        assertTrue(libraryDependency.isExported)

        val utils = ExternalSystemApiUtil.findAll(project, SwiftPackageData.KEY).single().data
        assertEquals("utils", utils.identity)
        assertEquals(checkout, utils.packagePath)
        assertFalse(utils.editable)
        assertEquals("1.2.0", utils.version)
    }

    @Test fun `a module gets the libraries a checkout product needs transitively`() {
        fun target(pkg: String, name: String, products: List<SwiftProductDependency> = emptyList(), product: String = name) =
            SwiftTarget(name, name, SwiftTargetKind.LIBRARY, SwiftModuleType.SWIFT, Path.of("$pkg/Sources/$name"), listOf("$name.swift"),
                emptyList(), products, listOf(product))
        fun pkg(identity: String, path: String, vararg targets: SwiftTarget) =
            SwiftPackage(identity, identity, Path.of(path), "6.0", targets.map { SwiftProduct(it.name, SwiftProductKind.LIBRARY, listOf(it.name)) }, targets.toList())

        val c = pkg("c", "/app/.build/checkouts/c", target("/app/.build/checkouts/c", "C"))
        val b = pkg("b", "/app/.build/checkouts/b", target("/app/.build/checkouts/b", "B", listOf(SwiftProductDependency("C", "c"))))
        val app = pkg("app", "/app", target("/app", "App", listOf(SwiftProductDependency("B", "b"))))
        fun node(p: SwiftPackage, vararg deps: SwiftResolvedPackage) = SwiftResolvedPackage(p.identity, p.name, "", "1.0.0", p.path, deps.toList())
        val graph = SwiftPackageGraph(app, node(app, node(b, node(c))), listOf(app, b, c).associateBy { it.identity })

        val project = SwiftPmProjectMapper.map(graph)
        val dependencies = ExternalSystemApiUtil.findAll(module(project, "app.App"), ProjectKeys.LIBRARY_DEPENDENCY).map { it.data.target.externalName }
        assertEquals(listOf("b:B", "c:C"), dependencies)
    }

    @Test fun `a target at the package directory takes over the content root`() {
        val target = SwiftTarget("Tool", "Tool", SwiftTargetKind.EXECUTABLE, SwiftModuleType.SWIFT, Path.of("/tool"), listOf("main.swift"),
            emptyList(), emptyList(), listOf("tool"))
        val pkg = SwiftPackage("tool", "Tool", Path.of("/tool"), "6.0", listOf(SwiftProduct("tool", SwiftProductKind.EXECUTABLE, listOf("Tool"))), listOf(target))
        val graph = SwiftPackageGraph(pkg, SwiftResolvedPackage("tool", "Tool", "", "unspecified", pkg.path, emptyList()), mapOf("tool" to pkg))

        val project = SwiftPmProjectMapper.map(graph)
        assertTrue(contentRoots(module(project, "Tool")).isEmpty())
        val root = contentRoots(module(project, "Tool.Tool")).single()
        assertEquals("/tool", root.rootPath)
        assertEquals(listOf("/tool"), root.getPaths(ExternalSystemSourceType.SOURCE).map { it.path })
        assertEquals(setOf("/tool/.build", "/tool/.swiftpm"), root.getPaths(ExternalSystemSourceType.EXCLUDED).map { it.path }.toSet())
    }

    @Test fun `module names stay unique ignoring case`() {
        // The root is named "Utils"; its path: dependency's name and identity are both "utils".
        val root = SwiftPackage("app", "Utils", Path.of("/app"), "6.0", emptyList(), emptyList())
        val dependency = SwiftPackage("utils", "utils", Path.of("/utils"), "6.0", emptyList(), emptyList())
        val tree = SwiftResolvedPackage("app", "Utils", "", "unspecified", root.path,
            listOf(SwiftResolvedPackage("utils", "utils", "", "unspecified", dependency.path, emptyList())))
        val graph = SwiftPackageGraph(root, tree, listOf(root, dependency).associateBy { it.identity })

        val names = packageModules(SwiftPmProjectMapper.map(graph)).map { it.data.externalName }
        assertEquals(listOf("Utils", "utils.2"), names)
    }

    @Test fun `module files go in the IDE project, not in the package`() {
        val project = SwiftPmProjectMapper.map(SwiftPmFixtures.greeterGraph(), ideProjectPath = "/work/ide")
        assertEquals("/work/ide", project.data.ideProjectFileDirectoryPath)
        assertEquals(G, project.data.linkedExternalProjectPath)
        assertTrue(ExternalSystemApiUtil.findAllRecursively(project, ProjectKeys.MODULE).all { it.data.moduleFileDirectoryPath == "/work/ide/.idea/modules" })
    }

    @Test fun `binary and system-library targets get no build task`() {
        val binary = SwiftTarget("Lib", "Lib", SwiftTargetKind.BINARY, SwiftModuleType.BINARY, Path.of("/bin/Lib.xcframework"), emptyList(),
            emptyList(), emptyList(), emptyList())
        val pkg = SwiftPackage("bin", "Bin", Path.of("/bin"), "6.0", emptyList(), listOf(binary))
        val graph = SwiftPackageGraph(pkg, SwiftResolvedPackage("bin", "Bin", "", "unspecified", pkg.path, emptyList()), mapOf("bin" to pkg))
        assertTrue(tasks(module(SwiftPmProjectMapper.map(graph), "Bin.Lib")).isEmpty())
    }

    @Test fun `version-specific manifests are settings files`() {
        assertTrue(SwiftPm.isSettingsFile("Package.swift"))
        assertTrue(SwiftPm.isSettingsFile("Package.resolved"))
        assertTrue(SwiftPm.isSettingsFile("Package@swift-6.0.swift"))
        assertTrue(SwiftPm.isSettingsFile("Package@swift-5.10.1.swift"))
        assertFalse(SwiftPm.isSettingsFile("Package@swift.swift"))
        assertFalse(SwiftPm.isSettingsFile("Greeter.swift"))
    }

    @Test fun `paths SwiftPM resolved through a symbolic link map back to the linked ones`() {
        val temp = java.nio.file.Files.createTempDirectory("swiftpm-paths").toRealPath()
        val real = java.nio.file.Files.createDirectories(temp.resolve("real/App"))
        val linked = java.nio.file.Files.createSymbolicLink(temp.resolve("link"), temp.resolve("real")).resolve("App")
        val mapping = SwiftPmPathMapping(linked)
        assertEquals(linked.resolve("Sources/App"), mapping.map(real.resolve("Sources/App")))
        // A sibling `path:` dependency, through the linked parent.
        assertEquals(temp.resolve("link/Utils"), mapping.map(temp.resolve("real/Utils")))
        assertEquals(Path.of("/elsewhere/X"), mapping.map(Path.of("/elsewhere/X")))
    }

    @Test fun `preview mode maps only the package directory`() {
        val project = SwiftPmProjectMapper.preview(Path.of(G))
        val module = packageModules(project).single()
        assertEquals("Greeter", module.data.externalName)
        assertEquals(SwiftPmProjectMapper.packageModuleId("greeter"), module.data.id)
        assertEquals(G, contentRoots(module).single().rootPath)
        assertTrue(targetModules(module).isEmpty())
    }

    @Test fun `task names round-trip`() {
        val tasks = listOf(SwiftPmTask.Build, SwiftPmTask.Test, SwiftPmTask.Clean, SwiftPmTask.Resolve, SwiftPmTask.Update,
            SwiftPmTask.BuildTarget("Greeter"), SwiftPmTask.Run("greet"))
        for (task in tasks) assertEquals(task, SwiftPmTask.parse(task.name))
        assertNull(SwiftPmTask.parse("run"))
        assertNull(SwiftPmTask.parse("run:"))
        assertNull(SwiftPmTask.parse("assemble"))
        assertNull(SwiftPmTask.parse("test:Greeter"))
    }

    @Test fun `manifest paths name their package directory`() {
        assertEquals(Path.of(G), SwiftPmPaths.packageDirectory("$G/${SwiftPackage.MANIFEST}"))
        assertEquals(Path.of(G), SwiftPmPaths.packageDirectory(G))
    }

    private fun packageModules(project: DataNode<ProjectData>): List<DataNode<ModuleData>> =
        ExternalSystemApiUtil.findAll(project, ProjectKeys.MODULE).toList()

    private fun targetModules(packageModule: DataNode<ModuleData>): List<DataNode<ModuleData>> =
        ExternalSystemApiUtil.findAll(packageModule, ProjectKeys.MODULE).toList()

    private fun module(project: DataNode<ProjectData>, name: String): DataNode<ModuleData> =
        ExternalSystemApiUtil.findAllRecursively(project, ProjectKeys.MODULE).single { it.data.externalName == name }

    private fun contentRoots(module: DataNode<ModuleData>) = ExternalSystemApiUtil.findAll(module, ProjectKeys.CONTENT_ROOT).map { it.data }

    private fun moduleDependencies(project: DataNode<ProjectData>, name: String): Set<String> =
        ExternalSystemApiUtil.findAll(module(project, name), ProjectKeys.MODULE_DEPENDENCY).map { it.data.target.externalName }.toSet()

    private fun tasks(module: DataNode<ModuleData>): List<String> = ExternalSystemApiUtil.findAll(module, ProjectKeys.TASK).map { it.data.name }
}
