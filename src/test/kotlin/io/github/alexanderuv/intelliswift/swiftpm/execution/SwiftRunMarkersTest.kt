package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.execution.TestStateStorage
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.swiftpm.execution.run.SwiftRunConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestScope
import java.util.Date

/**
 * Gutter markers and run configuration producers on a light SwiftPM package (in memory, so the
 * package is not described by SwiftPM: targets come from the standard `Sources/` and `Tests/`
 * layout).
 */
class SwiftRunMarkersTest : BasePlatformTestCase() {

    private val contributor = SwiftRunLineMarkerContributor()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("Package.swift", "// swift-tools-version:6.0\nimport PackageDescription\nlet package = Package(name: \"Pkg\")\n")
    }

    private val xcTests = """
        import XCTest

        class BaseCase: XCTestCase {}

        final class GreeterXCTests: BaseCase {
            func testGreeting() {}
            func testWith(argument: Int) {}
            func helper() {}
            static func testStatic() {}
            private func testPrivate() {}
        }

        extension GreeterXCTests {
            func testFromExtension() throws {}
        }

        final class NotATest: NSObject {
            func testNothing() {}
        }
    """.trimIndent()

    private val swiftTests = """
        import Testing

        @Suite("Greeter") struct GreeterSuite {
            @Test func loud() {}
            @Test("names", arguments: ["Ada"])
            func names(name: String) {}
            func helper() {}
            struct Inner {
                @Test func deep() {}
            }
        }

        struct Implicit {
            @Test(arguments: [1]) func unlabeled(_ value: Int) {}
        }

        extension Implicit {
            @Test func fromExtension() {}
        }

        @Test func topLevel() {}

        func notATest() {
            struct Local { @Test func local() {} }
        }
    """.trimIndent()

    /** Leaves that get a marker, as `text:line`. */
    private fun markers(file: PsiFile): List<String> =
        PsiTreeUtil.collectElements(file) { it.firstChild == null }.filter { contributor.getInfo(it) != null }.map { leaf ->
            "${leaf.text}:${myFixture.editor.document.getLineNumber(leaf.textOffset) + 1}"
        }

    private fun open(path: String, text: String): PsiFile {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return file
    }

    private fun element(file: PsiFile, text: String): PsiElement {
        val offset = file.text.indexOf(text)
        assertTrue("$text not found", offset >= 0)
        return file.findElementAt(offset)!!
    }

    fun `test XCTest classes and their test methods have markers`() {
        val file = open("Tests/PkgTests/GreeterXCTests.swift", xcTests)
        assertEquals(listOf("BaseCase:3", "GreeterXCTests:5", "testGreeting:6", "testFromExtension:14"), markers(file))
    }

    fun `test Swift Testing suites and functions have markers`() {
        val file = open("Tests/PkgTests/GreeterTests.swift", swiftTests)
        assertEquals(
            listOf("GreeterSuite:3", "loud:4", "names:6", "Inner:8", "deep:9", "Implicit:13", "unlabeled:14", "fromExtension:18", "topLevel:21"),
            markers(file),
        )
    }

    fun `test entry points of executable targets have markers`() {
        assertEquals(listOf("import:1"), markers(open("Sources/App/main.swift", "import Foundation\n\nprint(\"hi\")\n")))
        assertEquals(listOf("Tool:2"), markers(open("Sources/Tool/Tool.swift", "import Foundation\n@main struct Tool {\n    static func main() {}\n}\n")))
        // A file outside the target directories belongs to no target.
        assertEquals(emptyList<String>(), markers(open("Other/X.swift", "@main struct X {}\n")))
    }

    fun `test dependency checkouts have no markers`() {
        myFixture.addFileToProject(".build/checkouts/Dep/Package.swift", "// swift-tools-version:6.0\n")
        assertEquals(emptyList<String>(), markers(open(".build/checkouts/Dep/Tests/DepTests/DepTests.swift", xcTests)))
    }

    fun `test the marker shows the state of the last run`() {
        val file = open("Tests/PkgTests/GreeterXCTests.swift", xcTests)
        val method = element(file, "testGreeting")
        assertEquals(AllIcons.RunConfigurations.TestState.Run, contributor.getInfo(method)!!.icon)
        TestStateStorage.getInstance(project).writeState(
            "swift-xctest://PkgTests/GreeterXCTests/testGreeting",
            TestStateStorage.Record(TestStateInfo.Magnitude.FAILED_INDEX.value, Date(), 0, 0, null, null, null),
        )
        assertEquals(AllIcons.RunConfigurations.TestState.Red2, contributor.getInfo(method)!!.icon)
    }

    private fun testConfiguration(element: PsiElement): SwiftTestConfiguration {
        val context = ConfigurationContext(element)
        val fromContext = SwiftTestConfigurationProducer().createConfigurationFromContext(context)
        assertNotNull("No Swift Test configuration for ${element.text}", fromContext)
        val configuration = fromContext!!.configuration as SwiftTestConfiguration
        assertTrue(SwiftTestConfigurationProducer().isConfigurationFromContext(configuration, context))
        return configuration
    }

    private fun SwiftTestConfiguration.describe() = "$name | $scope | $testModule | $testSuite | $testName | $filter"

    fun `test producers create test configurations with filters`() {
        val xc = open("Tests/PkgTests/GreeterXCTests.swift", xcTests)
        assertEquals("GreeterXCTests | SUITE | PkgTests | GreeterXCTests | null | ^PkgTests\\.GreeterXCTests(/|$)",
            testConfiguration(element(xc, "GreeterXCTests: BaseCase")).describe())
        assertEquals("GreeterXCTests.testGreeting | TEST | PkgTests | GreeterXCTests | testGreeting | ^PkgTests\\.GreeterXCTests/testGreeting(/|$)",
            testConfiguration(element(xc, "{}\n    func testWith")).describe())
        assertEquals("GreeterXCTests.testFromExtension | TEST | PkgTests | GreeterXCTests | testFromExtension | ^PkgTests\\.GreeterXCTests/testFromExtension(/|$)",
            testConfiguration(element(xc, "testFromExtension")).describe())

        val st = open("Tests/PkgTests/GreeterTests.swift", swiftTests)
        assertEquals("GreeterSuite | SUITE | PkgTests | GreeterSuite | null | ^PkgTests\\.GreeterSuite(/|$)",
            testConfiguration(element(st, "GreeterSuite")).describe())
        assertEquals("GreeterSuite.names(name:) | TEST | PkgTests | GreeterSuite | names(name:) | ^PkgTests\\.GreeterSuite/names\\(name:\\)(/|$)",
            testConfiguration(element(st, "names(name")).describe())
        assertEquals("GreeterSuite/Inner.deep() | TEST | PkgTests | GreeterSuite/Inner | deep() | ^PkgTests\\.GreeterSuite/Inner/deep\\(\\)(/|$)",
            testConfiguration(element(st, "deep")).describe())
        assertEquals("Implicit.unlabeled(_:) | TEST | PkgTests | Implicit | unlabeled(_:) | ^PkgTests\\.Implicit/unlabeled\\(_:\\)(/|$)",
            testConfiguration(element(st, "unlabeled")).describe())
        assertEquals("Implicit.fromExtension() | TEST | PkgTests | Implicit | fromExtension() | ^PkgTests\\.Implicit/fromExtension\\(\\)(/|$)",
            testConfiguration(element(st, "fromExtension")).describe())
        assertEquals("topLevel() | TEST | PkgTests | null | topLevel() | ^PkgTests\\.topLevel\\(\\)(/|$)",
            testConfiguration(element(st, "topLevel")).describe())

        // A test target directory and the package directory.
        assertEquals("PkgTests | TARGET | PkgTests | null | null | ^PkgTests\\.", testConfiguration(st.containingDirectory!!).describe())
        assertEquals(SwiftTestScope.ALL, testConfiguration(st.containingDirectory!!.parentDirectory!!.parentDirectory!!).scope)

        assertNull(SwiftTestConfigurationProducer().createConfigurationFromContext(ConfigurationContext(element(st, "notATest"))))
        assertNull(SwiftTestConfigurationProducer().createConfigurationFromContext(ConfigurationContext(element(xc, "testNothing"))))
    }

    fun `test a configuration for another test is not from the context`() {
        val st = open("Tests/PkgTests/GreeterTests.swift", swiftTests)
        val configuration = testConfiguration(element(st, "loud"))
        assertFalse(SwiftTestConfigurationProducer().isConfigurationFromContext(configuration, ConfigurationContext(element(st, "topLevel"))))
        assertFalse(SwiftTestConfigurationProducer().isConfigurationFromContext(configuration, ConfigurationContext(element(st, "GreeterSuite"))))
    }

    fun `test producers create run configurations for main_swift and @main types`() {
        val producer = SwiftRunConfigurationProducer()
        val main = open("Sources/App/main.swift", "import Foundation\n\nprint(\"hi\")\n")
        val context = ConfigurationContext(element(main, "print"))
        val app = producer.createConfigurationFromContext(context)!!.configuration as SwiftRunConfiguration
        assertEquals("App", app.product)
        assertEquals("App", app.name)
        assertEquals(main.virtualFile.parent.parent.parent.path, app.packageDirectory)
        assertTrue(producer.isConfigurationFromContext(app, context))

        val tool = open("Sources/Tool/Tool.swift", "@main struct Tool {\n    static func main() {}\n}\n")
        val toolConfiguration = producer.createConfigurationFromContext(ConfigurationContext(element(tool, "main()")))!!.configuration as SwiftRunConfiguration
        assertEquals("Tool", toolConfiguration.product)
        assertFalse(producer.isConfigurationFromContext(toolConfiguration, context))

        val tests = open("Tests/PkgTests/GreeterTests.swift", swiftTests)
        assertNull(producer.createConfigurationFromContext(ConfigurationContext(element(tests, "topLevel"))))
    }
}
