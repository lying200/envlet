import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)

    intellijPlatform {
        // Modified for Envlet: IDEA 262 is unified and requires Java 25.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}
