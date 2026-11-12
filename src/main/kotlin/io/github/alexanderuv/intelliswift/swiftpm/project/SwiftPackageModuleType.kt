package io.github.alexanderuv.intelliswift.swiftpm.project

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleTypeManager
import javax.swing.Icon

/**
 * The type of the modules SwiftPM import creates, one per package and one per target. Modules
 * are only created by import, so the type offers no wizard.
 */
class SwiftPackageModuleType : ModuleType<SwiftPackageModuleType.Builder>(ID) {
    override fun createModuleBuilder(): Builder = Builder()
    override fun getName(): String = "Swift Package"
    override fun getDescription(): String = "A Swift package or target imported from SwiftPM"
    /**
     * The platform's module icon, as other build systems' modules have. The Swift logo is for
     * Swift files; this icon is also how the Project view shows a package's directory.
     */
    override fun getNodeIcon(isOpened: Boolean): Icon = AllIcons.Nodes.Module

    /** Not offered in the New Module wizard: Swift modules come from importing a package. */
    class Builder : ModuleBuilder() {
        override fun getModuleType(): ModuleType<*> = getInstance()
        override fun isAvailable(): Boolean = false
    }

    companion object {
        const val ID: String = "SWIFT_MODULE"

        fun getInstance(): SwiftPackageModuleType = ModuleTypeManager.getInstance().findByID(ID) as SwiftPackageModuleType
    }
}
