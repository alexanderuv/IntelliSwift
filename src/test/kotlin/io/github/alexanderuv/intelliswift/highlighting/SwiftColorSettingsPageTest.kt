package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SwiftColorSettingsPageTest : BasePlatformTestCase() {
    private val page = SwiftColorSettingsPage()

    fun testRegistered() {
        assertTrue(ColorSettingsPage.EP_NAME.extensionList.any { it is SwiftColorSettingsPage })
    }

    fun testEveryKeyHasOneDescriptor() {
        val keys = page.attributeDescriptors.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val declared = SwiftHighlighterColors::class.java.fields
            .filter { TextAttributesKey::class.java.isAssignableFrom(it.type) }
            .map { it.get(null) as TextAttributesKey }
        assertEquals(declared.toSet(), keys.toSet())
        assertTrue(page.additionalHighlightingTagToDescriptorMap.values.all { it in keys })
    }

    /** The tags in the demo text mark exactly what the annotator colours in that text. */
    fun testDemoTagsMatchAnnotator() {
        val (text, tagged) = stripTags(page.demoText, page.additionalHighlightingTagToDescriptorMap)
        myFixture.configureByText("demo.swift", text)
        val annotated = myFixture.doHighlighting()
            .mapNotNull { info -> info.forcedTextAttributesKey?.let { Triple(info.startOffset, info.endOffset, it) } }
            .toSet()
        fun show(s: Set<Triple<Int, Int, TextAttributesKey>>) =
            s.sortedBy { it.first }.joinToString("\n") { "'${text.substring(it.first, it.second)}' ${it.third.externalName}" }
        assertEquals(show(tagged), show(annotated))
    }

    private fun stripTags(demo: String, tags: Map<String, TextAttributesKey>): Pair<String, Set<Triple<Int, Int, TextAttributesKey>>> {
        val out = StringBuilder()
        val ranges = HashSet<Triple<Int, Int, TextAttributesKey>>()
        val open = ArrayDeque<Pair<String, Int>>()
        var i = 0
        val tag = Regex("</?([a-z]+)>")
        while (i < demo.length) {
            val m = tag.matchAt(demo, i)
            if (m != null && m.groupValues[1] in tags) {
                if (m.value.startsWith("</")) {
                    val (name, start) = open.removeLast()
                    assertEquals(name, m.groupValues[1])
                    ranges += Triple(start, out.length, tags.getValue(name))
                } else {
                    open.addLast(m.groupValues[1] to out.length)
                }
                i += m.value.length
            } else {
                out.append(demo[i])
                i++
            }
        }
        assertTrue(open.isEmpty())
        return out.toString() to ranges
    }
}
