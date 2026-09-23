import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(25) }

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junitJupiter)
        }
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.assertj)

    // Kept for the same reason as in :core — a test that needs a project would be a JUnit 3
    // test, and only the vintage engine runs those.
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)

    intellijPlatform {
        // Modified for Envlet: IDEA 262 is unified and requires Java 25.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}
