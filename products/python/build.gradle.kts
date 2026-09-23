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
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(libs.assertj)
    testImplementation(libs.kotlinx.serialization.json)
    implementation(project(":core"))
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        val localPython = providers.gradleProperty("localPythonPluginPath").orNull
        if (localPython != null) localPlugin(localPython)
        else plugin("PythonCore", "262.10968.63")
    }
}
