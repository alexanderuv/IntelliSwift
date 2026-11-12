package io.github.alexanderuv.intelliswift.parser

import com.intellij.testFramework.ParsingTestCase

/**
 * PSI tree snapshots for `src/test/testData/parser/<Name>.swift`, compared with `<Name>.txt`.
 * A missing `.txt` is generated on the first run (and the test fails); review it before committing.
 * The sources must also be accepted by swift-syntax; the conformance test checks token and
 * declaration agreement, these snapshots pin the tree shape.
 */
class SwiftParsingTest : ParsingTestCase("parser", "swift", SwiftParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = true

    private fun doValidTest() {
        doTest(true)
        ensureNoErrorElements()
    }

    fun testDeclarations() = doValidTest()

    fun testSwift6() = doValidTest()

    fun testExpressions() = doValidTest()

    fun testStatements() = doValidTest()

    /** Operators the lexer splits at a leading `<` or `>` are one leaf again unless used as angle brackets. */
    fun testOperators() = doValidTest()

    /** Malformed input: errors are reported and the parser resynchronises on the following declarations. */
    fun testRecovery() = doTest(true)
}
