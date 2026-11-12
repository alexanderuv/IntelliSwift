package io.github.alexanderuv.intelliswift.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SwiftFileType : LanguageFileType(SwiftLanguage) {
    override fun getName(): String = "Swift"

    override fun getDescription(): String = "Swift source file"

    override fun getDefaultExtension(): String = "swift"

    override fun getIcon(): Icon = SwiftIcons.FILE
}

object SwiftIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/swift.svg", SwiftIcons::class.java)
}
