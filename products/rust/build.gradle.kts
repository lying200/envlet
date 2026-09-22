import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(25) }

testing {
    suites {
        named<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junitJupiter) }
    }
}

dependencies {
    testImplementation(libs.assertj)
    implementation(project(":core"))
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        val localRust = providers.gradleProperty("localRustPluginPath").orNull
        if (localRust != null) localPlugin(localRust)
        else plugin("com.jetbrains.rust", "262.10968.75")
    }
}
