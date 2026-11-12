package io.github.alexanderuv.intelliswift.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.alexanderuv.intelliswift.lang.SwiftIcons
import io.github.alexanderuv.intelliswift.highlighting.SwiftHighlighterColors as C
import javax.swing.Icon

class SwiftColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = SwiftIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = SwiftSyntaxHighlighter()
    override fun getDemoText(): String = DEMO_TEXT
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Swift"

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keywords//Keyword", C.KEYWORD),
            AttributesDescriptor("Keywords//self, Self, super", C.SELF),
            AttributesDescriptor("Identifiers//Default", C.IDENTIFIER),
            AttributesDescriptor("Identifiers//Type declaration", C.CLASS_NAME),
            AttributesDescriptor("Identifiers//Protocol declaration", C.PROTOCOL_NAME),
            AttributesDescriptor("Identifiers//Generic parameter", C.TYPE_PARAMETER),
            AttributesDescriptor("Identifiers//Function declaration", C.FUNCTION_DECLARATION),
            AttributesDescriptor("Identifiers//Parameter", C.PARAMETER),
            AttributesDescriptor("Identifiers//Property", C.PROPERTY),
            AttributesDescriptor("Identifiers//Global variable", C.GLOBAL_VARIABLE),
            AttributesDescriptor("Identifiers//Local variable", C.LOCAL_VARIABLE),
            AttributesDescriptor("Identifiers//Enum case", C.ENUM_CASE),
            AttributesDescriptor("Number", C.NUMBER),
            AttributesDescriptor("String//String text", C.STRING),
            AttributesDescriptor("String//Escape sequence and interpolation", C.VALID_ESCAPE),
            AttributesDescriptor("String//Invalid escape sequence", C.INVALID_ESCAPE),
            AttributesDescriptor("Regex literal", C.REGEX),
            AttributesDescriptor("Comments//Line comment", C.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", C.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation comment", C.DOC_COMMENT),
            AttributesDescriptor("Operator", C.OPERATOR),
            AttributesDescriptor("Braces and Operators//Parentheses", C.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", C.BRACKETS),
            AttributesDescriptor("Braces and Operators//Braces", C.BRACES),
            AttributesDescriptor("Braces and Operators//Comma", C.COMMA),
            AttributesDescriptor("Braces and Operators//Semicolon", C.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Dot", C.DOT),
            AttributesDescriptor("Attribute", C.ATTRIBUTE),
            AttributesDescriptor("Compiler directive and macro expansion", C.DIRECTIVE),
            AttributesDescriptor("Bad character", C.BAD_CHARACTER),
        )

        /** Keys only the annotator applies; the demo text marks them with these tags. */
        internal val TAGS: Map<String, TextAttributesKey> = mapOf(
            "kw" to C.KEYWORD,
            "attr" to C.ATTRIBUTE,
            "directive" to C.DIRECTIVE,
            "type" to C.CLASS_NAME,
            "protocol" to C.PROTOCOL_NAME,
            "typeparam" to C.TYPE_PARAMETER,
            "func" to C.FUNCTION_DECLARATION,
            "param" to C.PARAMETER,
            "prop" to C.PROPERTY,
            "global" to C.GLOBAL_VARIABLE,
            "local" to C.LOCAL_VARIABLE,
            "case" to C.ENUM_CASE,
            "esc" to C.VALID_ESCAPE,
        )

        internal val DEMO_TEXT = """
            |#!/usr/bin/env swift
            |import Foundation
            |
            |/// A shape that can describe itself.
            |protocol <protocol>Shape</protocol>: Sendable {
            |    associatedtype <type>Unit</type>
            |    var <prop>area</prop>: Double { <kw>get</kw> }
            |}
            |
            |let <global>defaultName</global> = "shape"
            |
            |/* Shapes are values. */
            |@<attr>MainActor</attr>
            |struct <type>Circle</type>: Shape, CustomStringConvertible {
            |    var <prop>radius</prop>: Double = 1.5e2
            |    var <prop>area</prop>: Double { .pi * radius * radius }
            |    var <prop>description</prop>: String { "Circle(<esc>\(</esc>radius<esc>)</esc>)\n\q" }
            |
            |    <kw>nonisolated</kw> func <func>scaled</func>(by <param>factor</param>: Double) -> <kw>some</kw> Shape {
            |        let <local>result</local> = Circle(radius: radius * factor)
            |        return result
            |    }
            |}
            |
            |enum <type>Token</type><<typeparam>Payload</typeparam>> {
            |    case <case>word</case>(Payload), <case>end</case>
            |}
            |
            |<kw>actor</kw> <type>Counter</type> {
            |    private var <prop>value</prop> = 0x2A
            |    func <func>increment</func>() <kw>async</kw> throws -> Int {
            |        value += 1
            |        return self.value
            |    }
            |}
            |
            |#if DEBUG
            |let <global>pattern</global> = /[a-z]+(\d+)?/
            |let <global>caller</global> = #<directive>function</directive>
            |#endif
            |let <global>squares</global> = [1, 2, 3].map { <param>n</param> in n * n }; let <global>bad</global> = ``
            |""".trimMargin()
    }
}
