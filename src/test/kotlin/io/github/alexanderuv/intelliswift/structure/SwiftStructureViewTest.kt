package io.github.alexanderuv.intelliswift.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.psi.SwiftNamedDeclaration
import java.io.File

class SwiftStructureViewTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/structure"

    private fun model(): SwiftStructureViewModel {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file)
        assertInstanceOf(builder, TreeBasedStructureViewBuilder::class.java)
        val model = (builder as TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor)
        assertInstanceOf(model, SwiftStructureViewModel::class.java)
        Disposer.register(testRootDisposable) { model.dispose() }
        return model as SwiftStructureViewModel
    }

    private fun render(element: StructureViewTreeElement, indent: String = ""): String = buildString {
        val p = element.presentation
        append(indent).append(p.presentableText)
        p.locationString?.let { append(" [").append(it).append(']') }
        append(" (").append(iconName(p.getIcon(false))).append(")\n")
        for (child in element.children) append(render(child as StructureViewTreeElement, "$indent  "))
    }

    private fun iconName(icon: javax.swing.Icon?): String = ICONS.entries.firstOrNull { it.value == icon }?.key ?: "?"

    fun testStructure() {
        myFixture.configureByFile("Structure.swift")
        val root = model().root
        val actual = root.children.joinToString("") { render(it as StructureViewTreeElement) }
        val expected = """
            Shape (Interface)
              Unit (Type)
              area: Double (Property)
              scaled(by:) (Method)
            Circle [Shape, Equatable] (Record)
              Unit (Alias)
              radius: Double (Field)
              x: Int (Field)
              y: Int (Field)
              a (Field)
              b (Field)
              area: Double (Property)
              init(radius:) (Constructor)
              init?(_:) (Constructor)
              subscript(_:) (Property)
              subscript(row:column:) (Property)
              scaled(by:) (Method)
              ==(_:_:) (Method)
              Kind (Enum)
                small (Field)
                large(size: Int) (Field)
                huge (Field)
            Box (Class)
              deinit (Method)
            Counter (Class)
            extension Array (Class)
              totalArea() (Method)
            extension Circle [CustomStringConvertible] (Class)
              description: String (Property)
            debugOnly(_:_:) (Function)
            releaseOnly() (Function)
            globalValue (Variable)
            topLevel() (Function)

        """.trimIndent()
        assertEquals(expected, actual)
    }

    fun testFileNodeAndNavigation() {
        myFixture.configureByFile("Structure.swift")
        val root = model().root
        assertEquals("Structure.swift", root.presentation.presentableText)
        val circle = root.children.map { it as StructureViewTreeElement }.first { it.presentation.presentableText == "Circle" }
        val radius = circle.children.map { it as StructureViewTreeElement }.first { it.presentation.presentableText == "radius: Double" }
        val value = radius.value as PsiElement
        assertInstanceOf(value, SwiftNamedDeclaration::class.java)
        assertEquals("radius", (value as SwiftNamedDeclaration).name)
        assertTrue(radius.canNavigate())
    }

    fun testCurrentEditorElement() {
        myFixture.configureByText("a.swift", "struct S {\n    func f() {\n        let <caret>local = 1\n    }\n}")
        val element = model().currentEditorElement as PsiElement
        assertEquals("f", (element as SwiftNamedDeclaration).name)
    }

    fun testDocCommentDoesNotMoveNavigationOffset() {
        myFixture.configureByText("a.swift", "/// Doc\nstruct S {}\n\n/// Doc\nextension S {}")
        val text = myFixture.file.text
        val (s, ext) = model().root.children.map { (it as StructureViewTreeElement).value as PsiElement }
        assertEquals(text.indexOf("S {"), s.textOffset)
        assertEquals(text.indexOf("extension"), ext.textOffset)
    }

    private companion object {
        val ICONS = mapOf(
            "Class" to AllIcons.Nodes.Class, "Record" to AllIcons.Nodes.Record, "Enum" to AllIcons.Nodes.Enum,
            "Interface" to AllIcons.Nodes.Interface, "Alias" to AllIcons.Nodes.Alias, "Type" to AllIcons.Nodes.Type,
            "Function" to AllIcons.Nodes.Function, "Method" to AllIcons.Nodes.Method, "Constructor" to AllIcons.Nodes.Constructor,
            "Property" to AllIcons.Nodes.Property, "Field" to AllIcons.Nodes.Field, "Variable" to AllIcons.Nodes.Variable,
        )
    }
}
