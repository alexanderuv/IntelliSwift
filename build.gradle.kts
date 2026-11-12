import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // Classes inherit the platform interfaces' JVM default methods instead of getting `super`
        // stubs for them. The stubs exist for consumers compiled against DefaultImpls, which a
        // plugin has none of, and they made the plugin verifier report the platform's own internal
        // and deprecated default methods (ExternalSystemUnlinkedProjectAware) as used by us.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        allWarningsAsErrors.set(false)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    // Parses SwiftPM's JSON output; the platform does not expose a JSON library to plugins.
    implementation("com.google.code.gson:gson:2.13.2") {
        // Compile-time annotations only; nothing to ship.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Swift Test results: the SM test runner and the test runner UI, content modules of the
        // bundled "intellij.testRunner.plugin".
        bundledModule("intellij.platform.smRunner")
        bundledModule("intellij.platform.testRunner")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        // The conformance test compares against swift-syntax oracle output. It is skipped
        // unless that output is passed in; see scripts/conformance.sh.
        systemProperty("intelliswift.oracle.output", providers.gradleProperty("oracleOutput").getOrElse(""))
        systemProperty("intelliswift.conformance.maxFailures", providers.gradleProperty("conformanceMaxFailures").getOrElse("0"))
        maxHeapSize = "2g"
    }
}
