package io.github.alexanderuv.intelliswift.editor

import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.alexanderuv.intelliswift.lang.SwiftLanguage

class SwiftFoldingTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/folding"

    fun testFolding() {
        myFixture.testFoldingWithCollapseStatus("$testDataPath/Folding.swift")
    }

    fun testRegisteredAndDumbAware() {
        val builder = LanguageFolding.INSTANCE.allForLanguage(SwiftLanguage).single { it is SwiftFoldingBuilder }
        assertInstanceOf(builder, DumbAware::class.java)
    }
}
