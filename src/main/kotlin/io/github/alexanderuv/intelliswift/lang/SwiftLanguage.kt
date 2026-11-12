package io.github.alexanderuv.intelliswift.lang

import com.intellij.lang.Language

object SwiftLanguage : Language("Swift") {
    @Suppress("unused")
    private fun readResolve(): Any = SwiftLanguage

    override fun getDisplayName(): String = "Swift"

    override fun isCaseSensitive(): Boolean = true
}
