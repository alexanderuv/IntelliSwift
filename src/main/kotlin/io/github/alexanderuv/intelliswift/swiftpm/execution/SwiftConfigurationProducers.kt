package io.github.alexanderuv.intelliswift.swiftpm.execution

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import io.github.alexanderuv.intelliswift.swiftpm.execution.run.SwiftRunConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.run.SwiftRunConfigurationType
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfiguration
import io.github.alexanderuv.intelliswift.swiftpm.execution.test.SwiftTestConfigurationType
import java.nio.file.Path

/** Swift Test configurations for XCTest classes and methods, Swift Testing suites and functions, test targets and packages. */
class SwiftTestConfigurationProducer : LazyRunConfigurationProducer<SwiftTestConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(SwiftTestConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(
        configuration: SwiftTestConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val test = context.psiLocation?.let(SwiftRunContexts::test) ?: return false
        configuration.packageDirectory = test.packageDirectory
        configuration.scope = test.scope
        configuration.testModule = test.module
        configuration.testSuite = test.suite
        configuration.testName = test.name
        configuration.name = configuration.suggestedName() ?: "All Tests"
        sourceElement.set(test.element)
        return true
    }

    override fun isConfigurationFromContext(configuration: SwiftTestConfiguration, context: ConfigurationContext): Boolean {
        val test = context.psiLocation?.let(SwiftRunContexts::test) ?: return false
        return samePath(configuration.packageDirectory, test.packageDirectory) &&
            configuration.scope == test.scope &&
            configuration.testModule == test.module &&
            configuration.testSuite == test.suite &&
            configuration.testName == test.name
    }
}

/** Swift Run configurations for `main.swift` and `@main` types of executable targets. */
class SwiftRunConfigurationProducer : LazyRunConfigurationProducer<SwiftRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(SwiftRunConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(
        configuration: SwiftRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val run = context.psiLocation?.let(SwiftRunContexts::run) ?: return false
        configuration.packageDirectory = run.packageDirectory
        configuration.product = run.product
        configuration.name = run.product
        sourceElement.set(run.element)
        return true
    }

    override fun isConfigurationFromContext(configuration: SwiftRunConfiguration, context: ConfigurationContext): Boolean {
        val run = context.psiLocation?.let(SwiftRunContexts::run) ?: return false
        return samePath(configuration.packageDirectory, run.packageDirectory) && configuration.product == run.product
    }
}

private fun samePath(a: String?, b: String): Boolean {
    if (a == null) return false
    if (a == b) return true
    return runCatching { Path.of(a).normalize() == Path.of(b).normalize() }.getOrDefault(false)
}
