package io.github.alexanderuv.intelliswift.toolchain

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/** What *Settings | Languages & Frameworks | Swift* stores on Apply. */
class SwiftToolchainConfigurableTest : BasePlatformTestCase() {

    fun `test apply refuses a path that is not valid and stores a valid one normalized`() {
        val settings = SwiftToolchainSettings.getInstance()
        val configured = settings.swiftPath
        val configurable = SwiftToolchainConfigurable()
        try {
            val field = UIUtil.findComponentOfType(configurable.createComponent(), TextFieldWithBrowseButton::class.java)!!

            // NUL is not valid in a path on any platform.
            field.text = "/opt/sw\u0000ift"
            val error = runCatching { configurable.apply() }.exceptionOrNull()
            assertTrue("$error", error is ConfigurationException)
            assertEquals(configured, settings.swiftPath)

            field.text = "  \"/opt/swift/usr/bin/swift\" "
            configurable.apply()
            assertEquals("/opt/swift/usr/bin/swift", settings.swiftPath)
            assertEquals("/opt/swift/usr/bin/swift", field.text)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.swiftPath = configured
        }
    }
}
